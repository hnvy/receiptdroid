package app.receiptdroid.core

import java.util.Locale

/**
 * Tidying a payee's name as a receipt prints it ("TESCO STORES 3297", "CORNER CAFE LTD") into the name
 * you use ("Tesco", "Corner Cafe"), and learning the names you correct it to.
 */
object Payees {
    // Words that say which branch or what kind of company, not who: left out of a name, and of its key.
    private val LEGAL = Regex(
        "(?i)[,\\s]+(?:ltd\\.?|limited|llc|l\\.l\\.c\\.|inc\\.?|incorporated|corp\\.?|plc|gmbh(?:\\s*&\\s*co\\.?\\s*kg)?|ag|kg|ohg|" +
            "s\\.?a\\.?r?\\.?l?\\.?|sas|s\\.?r\\.?l\\.?|s\\.?p\\.?a\\.?|b\\.?v\\.?|n\\.?v\\.?|a/s|ab|oy|pty\\.?(?:\\s+ltd\\.?)?|co\\.?)$",
    )
    private val BRANCH = Regex("(?i)(?:\\s*[-#,]\\s*|\\s+)(?:(?:store|stores|branch|shop|outlet|filiale|magasin|no\\.?|nr\\.?|n°)\\s*)?#?\\s*\\d[\\d-]*$")
    private val BRANCH_WORDS = Regex("(?i)\\s+(?:stores?|branch|outlet|filiale)$")

    /**
     * The general tidying, with nothing learned: without a branch's number ("#12", "STORE 3297") or a
     * company's form (Ltd, GmbH, Inc), and in ALL CAPITALS made Title Case, keeping short ones like BP and
     * H&M. Never empty: a name with nothing else in it is left as it is.
     */
    fun clean(name: String): String {
        var s = name.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', '-', ':', ';', '*').trim()
        repeat(3) {
            s = s.replace(BRANCH, "").replace(LEGAL, "").replace(BRANCH_WORDS, "").trim().trimEnd(',', '-', '.').trim()
        }
        if (s.count { it.isLetter() } < 2) return name.trim()
        return if (shouting(s)) titleCase(s) else s
    }

    private fun shouting(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        return letters.length >= 4 && letters.count { it.isUpperCase() } >= letters.length * 0.8
    }

    private fun titleCase(s: String): String = s.split(' ').joinToString(" ") { word ->
        val letters = word.count { it.isLetter() }
        // Two or three letters with no vowel (BP, KFC) or with a sign in them (H&M) are an abbreviation, kept.
        if (letters <= 3 && (word.none { it.lowercaseChar() in "aeiouyäöüéèàáíóú" } || word.any { !it.isLetterOrDigit() })) word
        else word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            .replace(Regex("([-'’&./])(\\p{Ll})")) { m -> m.groupValues[1] + m.groupValues[2].uppercase(Locale.ROOT) }
            .replace(Regex("'S\\b"), "'s").replace(Regex("’S\\b"), "’s")
    }

    /**
     * What a name is known by, whatever its capitals, accents, branch number or company form: "TESCO
     * STORES 3297" and "Tesco Stores 12" are both "tesco". Empty for a name of no letters.
     */
    fun key(name: String): String {
        var s = name.replace(Regex("\\s+"), " ").trim()
        repeat(3) { s = s.replace(BRANCH, "").replace(LEGAL, "").replace(BRANCH_WORDS, "").trim().trimEnd(',', '-', '.').trim() }
        val plain = java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return plain.replace("&", " and ").replace(Regex("[^\\p{L}\\d]+"), " ").replace(Regex("\\d+"), " ").trim().replace(Regex("\\s+"), " ")
    }

    /**
     * The tidy name for [printed]: as you last corrected it to ([learned], by [key]), else [clean]ed; and
     * then, if your journal ([journal]) has a payee by the same key, as your journal spells it.
     */
    fun tidy(printed: String, learned: Map<String, String>, journal: List<String> = emptyList()): String {
        val k = key(printed)
        if (k.isNotEmpty()) learned[k]?.let { return it }
        val cleaned = clean(printed)
        val ck = key(cleaned)
        if (ck.isNotEmpty()) journal.firstOrNull { key(it) == ck }?.let { return it }
        return cleaned
    }

    /**
     * What to remember when a payee read as [printed] was sent as [sent], or null if there's nothing new:
     * tidying already gives it, or there's nothing to know it by.
     */
    fun learn(printed: String, sent: String, learned: Map<String, String>, journal: List<String> = emptyList()): Pair<String, String>? {
        val k = key(printed)
        val name = sent.trim()
        if (k.isEmpty() || name.isEmpty() || tidy(printed, learned, journal) == name) return null
        return k to name
    }

    /** Learned names, one per line: the key, a tab, the name. */
    fun encode(learned: Map<String, String>): String =
        learned.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }

    fun decode(text: String): Map<String, String> = text.lines().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0 || tab == line.lastIndex) null else line.substring(0, tab) to line.substring(tab + 1)
    }.toMap()
}
