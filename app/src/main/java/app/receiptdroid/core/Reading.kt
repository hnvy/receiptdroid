package app.receiptdroid.core

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** A line of text the recogniser found, and where on the photo it is (in the photo's pixels). */
data class OcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    val height: Float get() = bottom - top
}

/** The parts of the entry that are picked by tapping text on the receipt. */
enum class Part(val label: String) { PAYEE("Payee"), DATE("Date"), AMOUNT("Amount") }

/** What a tapped line gives for a part: the value for its box, and with an amount the currency beside it, if any. */
data class Picked(val value: String, val currency: String? = null)

/** A first reading of the whole receipt: for each part found, the line it's on and what it gives. */
data class Guess(val picks: Map<Part, Pair<Int, Picked>> = emptyMap())

/** How the phone and its owner write things, for reading dates. */
data class ReadingStyle(val today: LocalDate, val monthFirst: Boolean = false, val locale: Locale = Locale.ENGLISH)

/** Turning recognised lines into the parts of an entry: by tapping one ([pick]) or as a first guess ([guess]). */
object Reading {
    private fun words(vararg w: String) =
        Regex("(?<!\\p{L})(?:" + w.joinToString("|") { Regex.escape(it) } + ")(?!\\p{L})")

    private val TOTAL = words(
        "total", "amount due", "balance due", "amount payable", "to pay", "summe", "gesamt", "gesamtbetrag", "betrag",
        "zu zahlen", "totale", "importo", "importe", "montant", "à payer", "a payer", "totaal", "te betalen", "suma",
        "razem", "do zapłaty", "celkem", "toplam", "jumlah", "amount charged", "amount paid", "you paid", "charged",
    )
    // Lines with an amount that isn't what was spent: part-sums, tax, what was handed over and given back.
    private val NOT_TOTAL = words(
        "subtotal", "sub total", "sub-total", "zwischensumme", "sous-total", "sous total", "subtotale", "subtotaal",
        "tax", "vat", "gst", "hst", "pst", "mwst", "ust", "tva", "iva", "btw", "netto", "net", "excl",
        "change", "rückgeld", "wechselgeld", "rendu", "resto", "wisselgeld", "cash", "bar", "tendered", "given", "gegeben",
        "savings", "saved", "discount", "rabatt", "items", "points", "tip",
    )
    private val NOT_PAYEE = words(
        "receipt", "invoice", "tax invoice", "welcome", "thank you", "customer copy", "merchant copy", "kassenbon", "rechnung",
        "quittung", "ticket", "facture", "bon", "duplicate", "copy",
    )

    /**
     * What tapping [lines]'s line [index] gives for [part]; null if there's nothing of the kind there.
     * [slope] is how the receipt's rows run (see [slope]); worked out from [lines] if not given.
     */
    fun pick(part: Part, index: Int, lines: List<OcrLine>, style: ReadingStyle, slope: Float = slope(lines)): Picked? {
        val line = lines.getOrNull(index) ?: return null
        return when (part) {
            Part.PAYEE -> ShareText.tidy(ReceiptEntry(payee = line.text)).payee.takeIf { it.isNotEmpty() }?.let { Picked(it) }
            Part.DATE -> Dates.find(line.text, style.today, style.monthFirst, locale = style.locale)?.let { Picked(it.toString()) }
            Part.AMOUNT -> amount(index, lines, slope)
        }
    }

    /** A payee found elsewhere than by a tap (eg named by an email), as a tap on its line would give it. */
    fun pickedPayee(name: String): Picked = Picked(ShareText.tidy(ReceiptEntry(payee = name)).payee)

    /** Steepest a row is looked for at: a photo taken some 15° askew. */
    private const val MAX_SLOPE = 0.27f
    private const val SLOPE_STEPS = 135

    /**
     * How the receipt's rows run across the photo, as a slope (0: level; 0.06: about 3.5° down to the right).
     * A photo is seldom taken quite straight, and then a label and its amount at the far side aren't level:
     * at 3.5° across 400 pixels, the amount is a whole line lower. Receipts print a label at the left and
     * its amount at the right, line after line, so the slope is the one at which the most pairs of a line
     * and another to its right fall in a row. Short and long labels sit at different distances from their
     * amounts, so only the true slope lines them all up; a slope that pairs each label with the amount a
     * row off fits only some. Level, when nothing is paired at all.
     */
    fun slope(lines: List<OcrLine>): Float {
        // Each pair as how far right, how far down, and how far off a row may be.
        val pairs = ArrayList<FloatArray>()
        for (a in lines) for (b in lines) {
            if (b === a || b.left < a.right) continue
            val dx = b.centerX - a.centerX
            val dy = b.centerY - a.centerY
            if (abs(dy) > MAX_SLOPE * dx + maxOf(a.height, b.height)) continue
            pairs.add(floatArrayOf(dx, dy, 0.35f * minOf(a.height, b.height)))
        }
        if (pairs.isEmpty()) return 0f
        val slopes = FloatArray(2 * SLOPE_STEPS + 1) { i -> MAX_SLOPE * (i - SLOPE_STEPS) / SLOPE_STEPS }
        val counts = IntArray(slopes.size) { i -> pairs.count { (dx, dy, near) -> abs(dy - slopes[i] * dx) < near } }
        val most = counts.max()
        if (most == 0) return 0f
        // The slopes that fit best make a run: its middle. Of several such runs, the one nearest level.
        val start = slopes.indices.filter { counts[it] == most }.minBy { abs(slopes[it]) }
        var first = start
        var last = start
        while (first > 0 && counts[first - 1] == most) first--
        while (last < slopes.lastIndex && counts[last + 1] == most) last++
        return (slopes[first] + slopes[last]) / 2
    }

    /**
     * The amount on line [index]: the last one with decimals, else the last. A label like TOTAL is often
     * recognised apart from its number, so a line with none gives the amount furthest right in its row.
     * The currency is the one named on the line, or else in its row.
     */
    private fun amount(index: Int, lines: List<OcrLine>, slope: Float): Picked? {
        val line = lines[index]
        val row = row(index, lines, slope)
        val found = best(line.text)
            ?: row.filter { it != index && lines[it].left >= line.left }.sortedByDescending { lines[it].left }
                .firstNotNullOfOrNull { best(lines[it].text) }
            ?: return null
        val currency = Amounts.currencyIn(line.text) ?: row.firstNotNullOfOrNull { Amounts.currencyIn(lines[it].text) }
        return Picked(found.number, currency)
    }

    private fun best(text: String): Amounts.Found? =
        Amounts.findAll(text).let { all -> all.lastOrNull { it.hasDecimals } ?: all.lastOrNull() }

    /**
     * The lines in a row with line [index], itself among them, left to right: along [slope], their middles
     * are within about half a line's height of each other.
     */
    private fun row(index: Int, lines: List<OcrLine>, slope: Float): List<Int> {
        val line = lines[index]
        return lines.indices
            .filter { i ->
                val other = lines[i]
                i == index || abs((other.centerY - line.centerY) - slope * (other.centerX - line.centerX)) < 0.6f * maxOf(other.height, line.height)
            }
            .sortedBy { lines[it].left }
    }

    /**
     * A first reading. The payee is the first line of words at the top; the date is the first written with
     * its year; the amount is the largest on a line that says total (or amount due, Summe, à payer…) and
     * isn't a subtotal, tax or change, or failing that the largest amount with decimals anywhere else.
     */
    fun guess(lines: List<OcrLine>, style: ReadingStyle, slope: Float = slope(lines)): Guess {
        val picks = LinkedHashMap<Part, Pair<Int, Picked>>()
        val order = lines.indices.sortedBy { lines[it].top }
        fun dated(i: Int) = Dates.find(lines[i].text, style.today, style.monthFirst, needYear = true, locale = style.locale)

        order.firstOrNull { i ->
            val t = lines[i].text
            val letters = t.count { it.isLetter() }
            letters >= 2 && letters * 2 >= t.count { !it.isWhitespace() } && !NOT_PAYEE.matches(t.trim().lowercase()) && dated(i) == null
        }?.let { i -> pick(Part.PAYEE, i, lines, style, slope)?.let { picks[Part.PAYEE] = i to it } }

        order.firstNotNullOfOrNull { i -> dated(i)?.let { i to Picked(it.toString()) } }?.let { picks[Part.DATE] = it }

        // A line level with "change" or "tax" is theirs, even when it was recognised apart from the label.
        val notTotal = lines.indices.filter { NOT_TOTAL.containsMatchIn(lines[it].text.lowercase()) }
            .flatMap { row(it, lines, slope) }.toSet()
        val usable = order.filter { it !in notTotal }
        val labelled = usable.filter { TOTAL.containsMatchIn(lines[it].text.lowercase()) }
            .mapNotNull { i -> amount(i, lines, slope)?.let { i to it } }
        val any = usable.mapNotNull { i -> if (best(lines[i].text)?.hasDecimals == true) amount(i, lines, slope)?.let { i to it } else null }
        // "Charged to card ending 1234" says total too: an amount with decimals is the one meant.
        val priced = labelled.filter { '.' in it.second.value }.ifEmpty { labelled }
        priced.ifEmpty { any }.maxByOrNull { it.second.value.toBigDecimal() }?.let { picks[Part.AMOUNT] = it }
        return Guess(picks)
    }

    /** The line at ([x], [y]) on the photo, or the nearest within [slop] pixels of one; null if none is. */
    fun hit(lines: List<OcrLine>, x: Float, y: Float, slop: Float): Int? =
        lines.indices.filter { i ->
            val l = lines[i]
            x >= l.left - slop && x <= l.right + slop && y >= l.top - slop && y <= l.bottom + slop
        }.minByOrNull { i ->
            // How far outside the line itself: 0 inside it. Then the smaller line, which is harder to hit.
            val l = lines[i]
            val dx = maxOf(l.left - x, 0f, x - l.right)
            val dy = maxOf(l.top - y, 0f, y - l.bottom)
            dx * dx + dy * dy + (l.bottom - l.top) * 1e-3f
        }
}
