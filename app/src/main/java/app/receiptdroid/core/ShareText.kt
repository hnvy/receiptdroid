package app.receiptdroid.core

/**
 * What a receipt comes to: the parts of a transaction that Ledgedroid's Share patterns can pick out of
 * shared text. Each is as typed in its field; [ShareText.problems] says what's wrong with them, if anything.
 */
data class ReceiptEntry(
    /** 2026-11-12, or empty for Ledgedroid's own default (today). */
    val date: String = "",
    val payee: String = "",
    /** The first posting's account, eg expenses:groceries; empty to choose it in Ledgedroid. */
    val account: String = "",
    /** A plain number with a decimal point, eg 12.50 or -12.50. */
    val amount: String = "",
    /** One of $ € £ ¥ ₹ ₩ ₺ ₽, a three-letter code like CHF, or empty for the journal's usual currency. */
    val currency: String = "",
)

/** What's wrong with an entry, by field; all null when it can be sent. */
data class EntryProblems(
    val date: String? = null,
    val payee: String? = null,
    val account: String? = null,
    val amount: String? = null,
    val currency: String? = null,
) {
    val none: Boolean get() = date == null && payee == null && account == null && amount == null && currency == null
}

/**
 * The text shared to Ledgedroid, and the Share pattern that reads it there.
 *
 * The text is five lines, in the order of a journal entry and of quick add's prompts:
 *
 *     Receipt
 *     Date: 2026-11-12
 *     Payee: Corner Cafe
 *     Account: expenses:food
 *     Amount: 12.50 USD
 *
 * Every line is always there; a part that wasn't filled in is left empty after its label, which Ledgedroid
 * takes as "not given" and asks for as usual. [PATTERN] is the regular expression to add in Ledgedroid under
 * Settings › Share patterns: its named groups are the five that Share patterns know.
 */
object ShareText {
    /** The name suggested for the pattern in Ledgedroid. */
    const val PATTERN_NAME = "Receipt"

    /**
     * The Share pattern. Between lines it takes any white space, so Windows line ends or a trimmed empty
     * value still match. The payee and account run to the end of their line.
     */
    const val PATTERN =
        "Receipt\\s+Date: ?(?<date>[\\d-]*)\\s+Payee: ?(?<payee>.*)\\s+Account: ?(?<account>.*)\\s+Amount: ?(?<amount>-?[\\d.]+) ?(?<currency>\\S*)"

    /** An entry to try the pattern on in Ledgedroid (Share patterns › Try it). */
    val SAMPLE = ReceiptEntry("2026-11-12", "Corner Cafe", "expenses:food", "12.50", "USD")

    /** The currency signs Ledgedroid knows; anything else has to be a three-letter code. */
    const val SYMBOLS = "$€£¥₹₩₺₽"

    /** The text to share for [e], tidied (see [tidy]). */
    fun of(e: ReceiptEntry): String {
        val t = tidy(e)
        return buildString {
            append("Receipt\n")
            append("Date: ").append(t.date).append('\n')
            append("Payee: ").append(t.payee).append('\n')
            append("Account: ").append(t.account).append('\n')
            append("Amount: ").append(t.amount)
            if (t.currency.isNotEmpty()) append(' ').append(t.currency)
        }
    }

    /**
     * [e] with what a journal can't hold taken out: the payee and account on one line with single spaces,
     * without ; (it starts a comment) or | (it starts the note), and the payee not starting with the * ! (
     * that mean something else at the start of a description.
     */
    fun tidy(e: ReceiptEntry): ReceiptEntry = e.copy(
        date = e.date.trim(),
        payee = oneLine(e.payee).trimStart { it in "*!(" || it == ' ' },
        account = oneLine(e.account),
        amount = e.amount.trim().replace('\u2212', '-'),
        currency = e.currency.trim().let { c -> if (c.length == 3 && c.all { it.isLetter() }) c.uppercase() else c },
    )

    private fun oneLine(s: String): String =
        s.replace(Regex("[;|\\r\\n\\t]"), " ").replace(Regex("\\s+"), " ").trim()

    /** What has to be put right before [e] can be sent. Only the amount has to be there. */
    fun problems(e: ReceiptEntry): EntryProblems {
        val t = tidy(e)
        return EntryProblems(
            date = if (t.date.isEmpty() || Dates.iso(t.date) != null) null else "Write it like 2026-11-12",
            account = accountProblem(t.account),
            amount = when {
                t.amount.isEmpty() -> "Needed"
                !Regex("-?\\d+(\\.\\d+)?").matches(t.amount) -> "A number like 12.50"
                else -> null
            },
            currency = when {
                t.currency.isEmpty() -> null
                t.currency.length == 1 && t.currency in SYMBOLS -> null
                t.currency.length == 3 && t.currency.all { it in 'A'..'Z' } -> null
                else -> "$SYMBOLS or a code like CHF"
            },
        )
    }

    /** As Ledgedroid checks an account name, so that it takes the one sent. */
    private fun accountProblem(a: String): String? = when {
        a.isEmpty() -> null
        a.first() == '(' || a.first() == '[' -> "Can't start with ( or ["
        a.length > 1 && a[0] in "*!" && a[1] == ' ' -> "Can't start with * or !"
        a.startsWith(':') || a.endsWith(':') || a.contains("::") -> "Part of the name is empty"
        else -> null
    }
}
