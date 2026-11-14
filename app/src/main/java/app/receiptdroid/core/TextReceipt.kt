package app.receiptdroid.core

/**
 * Receipts that come as text rather than a picture: an email's body, shared or pasted. They're read the
 * same way as a photo's text, as lines, with no places on a page: one line under another.
 */
object TextReceipt {
    /** At most this many lines are read: a receipt's total is never further down than this. */
    const val MAX_LINES = 600

    /** [lines] as recognised lines would be, one row each, so that the reading treats them like a photo's. */
    fun asOcr(lines: List<String>): List<OcrLine> =
        lines.mapIndexed { i, text -> OcrLine(text, 0f, i * ROW, WIDTH, i * ROW + HEIGHT) }

    private const val ROW = 30f
    private const val HEIGHT = 20f
    private const val WIDTH = 1000f

    /**
     * The lines of a receipt from an email: [subject] first (without Re:, Fwd:), then each line of [body]
     * with something in it, without the > of a quoted or forwarded message. A label on a line of its own
     * followed by an amount on the next, as an HTML table comes out (Total / £13.10), is made one line.
     */
    fun lines(subject: String?, body: String): List<String> {
        val out = ArrayList<String>()
        subject?.let { s -> subject(s).takeIf { it.isNotEmpty() }?.let { out.add(it) } }
        for (raw in body.lines()) {
            val line = raw.replace(QUOTE, "").replace(SPACES, " ").trim()
            if (line.any { it.isLetterOrDigit() }) out.add(line)
            if (out.size >= MAX_LINES) break
        }
        return joinLabels(out)
    }

    /** An email's subject without Re:, Fwd: and the like before it. */
    fun subject(s: String): String =
        s.replace(Regex("^(?:\\s*(?:re|fwd?|aw|wg|tr)\\s*:\\s*)+", RegexOption.IGNORE_CASE), "").replace(SPACES, " ").trim()

    private val SPACES = Regex("[\\s\\u00A0\\u200B]+")
    private val QUOTE = Regex("^(?:\\s*>)+")

    private fun joinLabels(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            if (next != null && line.length <= 40 && line.any { it.isLetter() } && Amounts.findAll(line).isEmpty() && amountOnly(next)) {
                out.add("$line $next")
                i += 2
            } else {
                out.add(line)
                i++
            }
        }
        return out
    }

    /** Whether [line] is an amount and nothing else, but for its currency: £13.10, 12,50 EUR, R$ 9,90. */
    private fun amountOnly(line: String): Boolean {
        val found = Amounts.findAll(line)
        if (found.size != 1 || !found[0].hasDecimals) return false
        val left = line.removeRange(found[0].start, found[0].end)
            .replace(Regex("\\b[A-Z]{3}\\b"), "")
            .replace(Regex("(?i)(?:US|HK|NZ|R|S|A|C)\\$|\\b(?:z\u0142|K\u010d|Rs\\.?|RM|Rp|Ft|S?Fr)(?!\\p{L})"), "")
            .filterNot { it in ShareText.SYMBOLS || it in "-\u2212+:*()\u20ab\u20b1\u0e3f\u20b4\u20aa\u20a6" || it.isWhitespace() }
        return left.isEmpty()
    }

    // How emails name who the receipt is from.
    private val NAMED = listOf(
        // Your receipt from Corner Cafe; Order with Acme Ltd; Thanks for shopping at Corner Cafe!
        Regex("(?i)\\b(?:receipt|order|purchase|payment|invoice|booking|transaction|shopping|visit)s?\\s+(?:from|at|with)\\s+(.+?)(?:\\s+[-\\u2013\\u2014|(]|\\s*[:.!]?\\s*$|\\s+on\\s+\\d|\\s+for\\s|\\s+#)"),
        // Your Corner Cafe receipt; Your Acme order
        Regex("(?i)^your\\s+(.+?)\\s+(?:receipt|order|invoice|purchase|booking)\\b"),
        // From: Corner Cafe <receipts@cornercafe.example>; Merchant: Corner Cafe
        Regex("(?i)^(?:from|merchant|seller|sold by|vendor|store|shop)\\s*:\\s*\"?(.+?)\"?\\s*(?:<.*)?$"),
    )

    /** Who an email says the receipt is from, and the line it says it on; null if none says. */
    fun payee(lines: List<String>): Pair<Int, String>? {
        for ((i, line) in lines.withIndex()) {
            for (re in NAMED) {
                val name = re.find(line)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', '!', ':', ';')?.trim() ?: continue
                if (name.count { it.isLetter() } >= 2 && name.length <= 60 && '@' !in name) return i to name
            }
        }
        return null
    }

    /** Whether [text] is only a link: a receipt online, which this app (with no internet) can't open. */
    fun isLink(text: String): Boolean = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(text.trim())

    /**
     * Plain text from an email's HTML: each paragraph, row and break on a line of its own, a table's cells
     * apart on their row, and no styles, scripts or markup.
     */
    fun fromHtml(html: String): String {
        var s = html
        for (drop in listOf("<!--.*?-->", "<head\\b.*?</head\\s*>", "<style\\b.*?</style\\s*>", "<script\\b.*?</script\\s*>", "<title\\b.*?</title\\s*>")) {
            s = s.replace(Regex(drop, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        }
        s = s.replace(Regex("\\s+"), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(?:p|div|tr|li|ul|ol|h[1-6]|table|tbody|thead|tfoot|section|article|header|footer|blockquote|center)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE), "  ")
            .replace(Regex("<[^>]*>"), "")
        s = entities(s)
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "pound" to "£",
        "euro" to "€", "yen" to "¥", "cent" to "¢", "copy" to "©", "reg" to "®", "trade" to "™", "ndash" to "–",
        "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "times" to "×",
        "middot" to "·", "bull" to "•", "eacute" to "é", "egrave" to "è", "agrave" to "à", "auml" to "ä", "ouml" to "ö",
        "uuml" to "ü", "szlig" to "ß", "ccedil" to "ç", "ntilde" to "ñ",
    )

    private fun entities(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { cp -> String(Character.toChars(cp)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { cp -> String(Character.toChars(cp)) }
            else -> NAMED_ENTITIES[e.lowercase()]
        } ?: m.value
    }
}
