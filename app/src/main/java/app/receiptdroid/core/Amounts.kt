package app.receiptdroid.core

import java.math.BigDecimal

/** Reading amounts of money, and what currency they're in, off a receipt. */
object Amounts {
    /** An amount in a line: the number as it's sent (12.50, -1234.56) and where it was. */
    data class Found(val number: String, val start: Int, val end: Int) {
        val value: BigDecimal get() = BigDecimal(number)
        val hasDecimals: Boolean get() = '.' in number
    }

    // The ways receipts write a number, the longest first: 1 234,56 and 1'234.56, 1.234,56 and 25.000,
    // 1,234.56 and 25,000, then 12.50 or 12,50, then a whole number.
    private val NUMBER = Regex(
        "(?<![\\d.,])(?:" +
            "\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+,\\d{2}" +
            "|\\d{1,3}(?:'\\d{3})+(?:[.,]\\d{2})?" +
            "|\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?" +
            "|\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?" +
            "|\\d+[.,]\\d{1,2}" +
            "|\\d+" +
            ")(?![\\d]|[.,]\\d)",
    )

    private val MINUS_BEFORE = Regex("[-\\u2212](?:[" + Regex.escape(ShareText.SYMBOLS) + "] ?)?$")

    /** Every amount in [text], in order. A minus right before it (or before its currency sign) or right after makes it negative. */
    fun findAll(text: String): List<Found> = NUMBER.findAll(text).mapNotNull { m ->
        val plain = plain(m.value) ?: return@mapNotNull null
        // -5.00, -$5.00 or -$ 5.00, but not the dash in "10-12" or in "Lane 3 - 5.00".
        val before = MINUS_BEFORE.find(text.substring(0, m.range.first))
            ?.let { it.range.first == 0 || !text[it.range.first - 1].isLetterOrDigit() } == true
        val after = text.getOrNull(m.range.last + 1).let { it == '-' || it == '\u2212' } &&
            text.getOrNull(m.range.last + 2)?.isLetterOrDigit() != true
        val negative = (before || after) && BigDecimal(plain).signum() != 0
        Found(if (negative) "-$plain" else plain, m.range.first, m.range.last + 1)
    }.toList()

    /** A matched number without its digit grouping and with a decimal point. */
    private fun plain(s: String): String? {
        val t = s.filter { it.isDigit() || it == '.' || it == ',' }
        val dots = t.count { it == '.' }
        val commas = t.count { it == ',' }
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        val out = when {
            dots > 0 && commas > 0 -> if (lastDot > lastComma) t.replace(",", "") else t.replace(".", "").replace(',', '.')
            dots + commas > 1 -> t.replace(".", "").replace(",", "")
            dots + commas == 1 -> {
                val at = maxOf(lastDot, lastComma)
                val decimals = t.length - at - 1
                // 25.000 is twenty-five thousand; 0.125 and 12.50 are what they look like.
                if (decimals == 3 && t.substring(0, at).trimStart('0').isNotEmpty()) t.removeRange(at, at + 1) else t.replace(',', '.')
            }
            else -> t
        }
        return out.trimStart('0').let { if (it.isEmpty() || it.startsWith('.')) "0$it" else it }.takeIf { it.any(Char::isDigit) }
    }

    /** What's typed in the amount box, kept to what can be sent: digits, a decimal point, a minus first. */
    fun typed(s: String): String {
        val t = s.replace(',', '.').replace('\u2212', '-').filter { it.isDigit() || it == '.' || it == '-' }
        val negative = t.startsWith('-')
        val digits = t.replace("-", "")
        val dot = digits.indexOf('.')
        val one = if (dot < 0) digits else digits.substring(0, dot + 1) + digits.substring(dot + 1).replace(".", "")
        return (if (negative) "-" else "") + one
    }

    // Written before or after an amount, and the code Ledgedroid wants for it. Longer ones first: R$ before $.
    private val WRITTEN = listOf(
        "US$" to "USD", "HK$" to "HKD", "NZ$" to "NZD", "R$" to "BRL", "S$" to "SGD", "A$" to "AUD", "C$" to "CAD",
        "zł" to "PLN", "Kč" to "CZK", "₫" to "VND", "₱" to "PHP", "฿" to "THB", "₴" to "UAH", "₪" to "ILS", "₦" to "NGN",
    )
    private val WORDS = mapOf("Rs" to "INR", "RM" to "MYR", "Rp" to "IDR", "Ft" to "HUF", "Fr" to "CHF", "SFr" to "CHF")
    private val CODES = setOf(
        "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "SEK", "NOK", "DKK", "ISK", "PLN", "CZK", "HUF", "RON",
        "BGN", "RSD", "UAH", "TRY", "ILS", "AED", "SAR", "QAR", "EGP", "ZAR", "NGN", "KES", "MAD", "INR", "PKR", "BDT",
        "LKR", "NPR", "CNY", "HKD", "TWD", "KRW", "SGD", "MYR", "THB", "IDR", "PHP", "VND", "MXN", "BRL", "ARS", "CLP",
        "COP", "RUB",
    )
    private val WORD = Regex("\\p{L}+")

    /** The currency [text] names, as Ledgedroid reads one: a sign it knows ($ € £ ¥ ₹ ₩ ₺ ₽) or a three-letter code. */
    fun currencyIn(text: String): String? {
        for ((written, code) in WRITTEN) if (written in text) return code
        text.firstOrNull { it in ShareText.SYMBOLS }?.let { return it.toString() }
        for (m in WORD.findAll(text)) {
            if (m.value in CODES) return m.value
            WORDS[m.value]?.let { return it }
        }
        return null
    }

    // What a sign can stand for. Several countries write $, so a receipt's "$" doesn't say which dollar.
    private val SIGN_MEANS = mapOf(
        "$" to setOf("USD", "CAD", "AUD", "NZD", "SGD", "HKD", "TWD", "MXN", "ARS", "CLP", "COP"),
        "€" to setOf("EUR"), "£" to setOf("GBP", "EGP"), "¥" to setOf("JPY", "CNY"),
        "₹" to setOf("INR"), "₩" to setOf("KRW"), "₺" to setOf("TRY"), "₽" to setOf("RUB"),
    )

    private fun means(currency: String): Set<String> = SIGN_MEANS[currency] ?: setOf(currency.uppercase())

    /**
     * The currency to put in the box when the receipt names [printed] and [mine] is what was used before.
     * If they can be the same currency, [mine] is kept, written the way the journal has it: a receipt's
     * "USD" or "$" stays the "$" (or "CAD") chosen last time. A different currency, eg on a trip, is taken
     * as printed.
     */
    fun preferred(printed: String?, mine: String): String = when {
        printed == null -> mine
        mine.isEmpty() -> printed
        means(printed).intersect(means(mine)).isNotEmpty() -> mine
        else -> printed
    }
}
