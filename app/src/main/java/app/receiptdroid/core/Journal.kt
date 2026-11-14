package app.receiptdroid.core

/**
 * The payees and accounts in an hledger journal, to suggest: each with how often it's used, the most
 * used first, and for each payee the account its latest transaction starts with.
 */
data class JournalNames(
    val payees: List<String> = emptyList(),
    val accounts: List<String> = emptyList(),
    /** By [Payees.key] of a payee: the first account of its latest transaction. */
    val usual: Map<String, String> = emptyMap(),
    /** Files the journal includes, which aren't read unless they're chosen too. */
    val includes: List<String> = emptyList(),
) {
    val empty: Boolean get() = payees.isEmpty() && accounts.isEmpty()

    /** The account [payee]'s transactions usually start with, if the journal has it. */
    fun usualFor(payee: String): String? = Payees.key(payee).takeIf { it.isNotEmpty() }?.let { usual[it] }

    /** The journal's payee known by [key] (see [Payees.key]), as the journal spells it; the most used, if several. */
    fun spelling(key: String): String? = index.byKey[key]

    /**
     * Each payee's key and words, worked out once, so that suggesting compares these and not the names: on
     * every keystroke, with thousands of payees. Ready as soon as the journal's [read]; made on first use for
     * names given otherwise.
     */
    internal val index: PayeeIndex by lazy { PayeeIndex(payees) }

    companion object {
        /**
         * Read [texts], the journal's files. Only what's needed for names: transactions' descriptions
         * (the payee, before any | note) and their postings' accounts, and account and payee directives.
         * Anything it doesn't understand is passed over.
         */
        fun read(texts: List<String>): JournalNames {
            val payeeUses = LinkedHashMap<String, Int>()
            val accountUses = LinkedHashMap<String, Int>()
            // Per payee key: (date, order) of the latest transaction, and its first account.
            val latest = HashMap<String, Pair<String, Int>>()
            val usual = HashMap<String, String>()
            val includes = ArrayList<String>()
            val keys = HashMap<String, String>() // Each payee's key, worked out once however many transactions it has.
            var order = 0

            for (text in texts) {
                var payee: String? = null // The transaction being read; null between transactions.
                var date = ""
                var first = true
                var inComment = false
                for (raw in text.lines()) {
                    val line = raw.trimEnd()
                    if (inComment) {
                        if (line.trim().equals("end comment", ignoreCase = true)) inComment = false
                        continue
                    }
                    if (line.isBlank()) { payee = null; continue }
                    val indented = line[0] == ' ' || line[0] == '\t'
                    if (indented) {
                        // A posting of the transaction being read, or a comment: an account and maybe an amount.
                        val p = payee ?: continue
                        val body = line.trim()
                        if (body.startsWith(';') || body.startsWith('#')) continue
                        val account = account(body) ?: continue
                        accountUses.merge(account, 1, Int::plus)
                        if (first) {
                            first = false
                            val k = keys.getOrPut(p) { Payees.key(p) }
                            val was = latest[k]
                            if (k.isNotEmpty() && (was == null || date > was.first || date == was.first && order > was.second)) {
                                latest[k] = date to order
                                usual[k] = account
                            }
                        }
                        continue
                    }
                    payee = null
                    when {
                        line.equals("comment", ignoreCase = true) -> inComment = true
                        line.startsWith("account ") -> account(line.removePrefix("account ").trim())?.let { accountUses.putIfAbsent(it, 0) }
                        line.startsWith("payee ") -> line.removePrefix("payee ").substringBefore(';').trim().takeIf { it.isNotEmpty() }
                            ?.let { payeeUses.putIfAbsent(it, 0) }
                        line.startsWith("include ") -> includes.add(line.removePrefix("include ").substringBefore(';').trim())
                        else -> HEADER.matchEntire(line)?.let { m ->
                            val description = m.groupValues[2].substringBefore(';').substringBefore('|').trim()
                            if (description.isNotEmpty()) {
                                payee = description
                                date = normalDate(m.groupValues[1])
                                first = true
                                order++
                                payeeUses.merge(description, 1, Int::plus)
                            }
                        }
                    }
                }
            }
            fun ranked(uses: Map<String, Int>) = uses.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }).map { it.key }
            // Made here, while reading in the background, not on the first keystroke.
            return JournalNames(ranked(payeeUses), ranked(accountUses), usual, includes).also { it.index }
        }

        // A transaction's first line: its date (and maybe =date2), maybe * or !, maybe (code), the description.
        private val HEADER = Regex("(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2})(?:=\\S+)?\\s+(?:[*!]\\s*)?(?:\\([^)]*\\)\\s*)?(.*)")

        /** 2026-1-2 as 2026-01-02, for comparing; one without a year sorts first. */
        private fun normalDate(d: String): String {
            val parts = d.split('-', '/', '.')
            return if (parts.size == 3) "%s-%02d-%02d".format(parts[0], parts[1].toInt(), parts[2].toInt()) else "0000-%02d-%02d".format(parts[0].toInt(), parts[1].toInt())
        }

        /** A posting's account: up to two spaces or a tab, without a status mark or the ( ) [ ] of a virtual posting. */
        private fun account(body: String): String? {
            var s = body.replace(Regex("^[*!]\\s+"), "")
            s = s.split(Regex("\\t|  "), limit = 2)[0].substringBefore(';').trim()
            if (s.length >= 2 && (s.first() == '(' && s.last() == ')' || s.first() == '[' && s.last() == ']')) s = s.substring(1, s.length - 1).trim()
            return s.takeIf { it.isNotEmpty() && it.any { c -> c.isLetter() } && !it.startsWith(':') && !it.endsWith(':') }
        }
    }
}

/** Choosing names to suggest, from what's been typed or read. */
object Suggest {
    // Words many businesses' names have: sharing one doesn't make two names alike.
    private val GENERIC = setOf(
        "the", "and", "cafe", "coffee", "restaurant", "bar", "pub", "shop", "store", "market", "supermarket", "kitchen",
        "bakery", "pharmacy", "hotel", "grill", "pizza", "express", "online", "services", "group", "company",
        "der", "die", "das", "und", "les", "des", "del", "los", "las",
    )

    /**
     * Payees from [journal] like [typed], best first: the same by [Payees.key], one starting the other,
     * sharing words, or containing it. Not [typed] itself.
     */
    fun payees(typed: String, journal: JournalNames, limit: Int = 3): List<String> {
        val k = Payees.key(typed)
        if (k.isEmpty()) return emptyList()
        val words = k.split(' ').toSet()
        val telling = words.filter { it.length >= 3 && it !in GENERIC }
        val name = typed.trim()
        val index = journal.index
        val found = ArrayList<Pair<Int, Int>>() // Score, and place in the journal's order (most used first).
        for (i in index.names.indices) {
            val nk = index.keys[i]
            if (nk.isEmpty() || index.names[i] == name) continue
            val score = when {
                nk == k -> 100
                nk.startsWith(k) || k.startsWith(nk) -> 80
                else -> {
                    val nw = index.words[i]
                    val shared = telling.count { it in nw }
                    if (shared > 0) 40 + 40 * shared / maxOf(nw.size, words.size)
                    else if (k.length >= 3 && nk.contains(k)) 50 else 0
                }
            }
            if (score >= 40) found.add(score to i)
        }
        return found.sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenBy { it.second }).take(limit).map { index.names[it.second] }
    }

    /**
     * Accounts to offer: with nothing typed, [first] (eg the payee's usual one) and then the most used;
     * otherwise those with a part starting with what's typed, then those containing it.
     */
    fun accounts(typed: String, accounts: List<String>, first: List<String> = emptyList(), limit: Int = 8): List<String> {
        val t = typed.trim().lowercase()
        val ordered = (first + accounts).distinct()
        if (t.isEmpty()) return ordered.take(limit)
        val starts = ordered.filter { a -> a.lowercase().let { it.startsWith(t) || it.split(':').any { part -> part.startsWith(t) } } }
        val contains = ordered.filter { it.lowercase().contains(t) && it !in starts }
        return (starts + contains).filter { !it.equals(typed.trim(), ignoreCase = true) }.take(limit)
    }
}

/** A journal's payees with each one's [Payees.key] and the words in it, worked out once. */
internal class PayeeIndex(payees: List<String>) {
    val names: List<String> = payees
    val keys: List<String> = payees.map { Payees.key(it) }
    val words: List<Set<String>> = keys.map { if (it.isEmpty()) emptySet() else it.split(' ').toHashSet() }
    /** The first payee (the most used) for each key. */
    val byKey: Map<String, String> = HashMap<String, String>().also { m ->
        for (i in payees.indices) if (keys[i].isNotEmpty()) m.putIfAbsent(keys[i], payees[i])
    }
}
