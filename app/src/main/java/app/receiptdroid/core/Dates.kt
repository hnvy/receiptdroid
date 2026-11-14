package app.receiptdroid.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Reading the date printed on a receipt. */
object Dates {
    private val ISO = Regex("(?<![\\d.,/-])(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)")
    private val NUMERIC = Regex("(?<![\\d.,/-])(\\d{1,2})[-/.](\\d{1,2})(?:[-/.](\\d{4}|\\d{2}))?(?!\\d)")
    // 12 Nov 2026, 12-Nov-26, 12. November 2026; and Nov 12, 2026. A year isn't taken from a time (12 Nov 14:32).
    private val DAY_MONTH = Regex("(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[ .-]{0,2}(\\p{L}{3,10})\\.?(?:,?[ .-]{0,2}(\\d{4}|\\d{2})(?![\\d:]))?")
    private val MONTH_DAY = Regex("(?<!\\p{L})(\\p{L}{3,10})\\.?[ .-]{0,2}(\\d{1,2})(?:st|nd|rd|th)?(?![\\d:])(?:,?[ .-]{0,2}(\\d{4}|\\d{2})(?![\\d:]))?")

    /** A date written the way it's sent, 2026-11-12; null for anything else. */
    fun iso(s: String): LocalDate? {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(s.trim()) ?: return null
        return dateOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /**
     * The first date in [text], preferring one written with its year. Numbers like 03/04 are read day first
     * unless [monthFirst] (as the phone's own date order says), or unless only one way round is a date. With
     * [needYear], a date without a year isn't one: otherwise a price like 12.10 would pass for the 12th of October.
     * Month names are read in English and in [locale]'s language.
     */
    fun find(
        text: String,
        today: LocalDate,
        monthFirst: Boolean = false,
        needYear: Boolean = false,
        locale: Locale = Locale.ENGLISH,
    ): LocalDate? {
        // Where it is, the date, and whether it had a year.
        val found = ArrayList<Triple<Int, LocalDate, Boolean>>()
        fun add(at: Int, year: String?, make: (Int) -> LocalDate?) {
            val d = if (year.isNullOrEmpty()) recent(today, make) else make(fullYear(year))
            if (d != null && d.year in 2000..today.year + 1) found.add(Triple(at, d, !year.isNullOrEmpty()))
        }
        for (m in ISO.findAll(text)) {
            val (y, mo, d) = m.destructured
            add(m.range.first, y) { yy -> dateOf(yy, mo.toInt(), d.toInt()) }
        }
        for (m in DAY_MONTH.findAll(text)) {
            val month = monthOf(m.groupValues[2], locale) ?: continue
            add(m.range.first, m.groupValues[3]) { y -> dateOf(y, month, m.groupValues[1].toInt()) }
        }
        for (m in MONTH_DAY.findAll(text)) {
            val month = monthOf(m.groupValues[1], locale) ?: continue
            add(m.range.first, m.groupValues[3]) { y -> dateOf(y, month, m.groupValues[2].toInt()) }
        }
        for (m in NUMERIC.findAll(text)) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val (day, month) = when {
                a > 12 -> a to b
                b > 12 -> b to a
                monthFirst -> b to a
                else -> a to b
            }
            add(m.range.first, m.groupValues[3]) { y -> dateOf(y, month, day) }
        }
        val byPlace = found.sortedBy { it.first }
        return (byPlace.firstOrNull { it.third } ?: byPlace.firstOrNull().takeIf { !needYear })?.second
    }

    private fun fullYear(y: String): Int = if (y.length == 2) 2000 + y.toInt() else y.toInt()

    /** Without a year: this year, or last year if that would be more than a day ahead. */
    private fun recent(today: LocalDate, make: (Int) -> LocalDate?): LocalDate? {
        val d = make(today.year) ?: return null
        return if (d.isAfter(today.plusDays(1))) make(today.year - 1) else d
    }

    private fun dateOf(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (e: DateTimeException) {
        null
    }

    /** A month's number from its name or the start of it (at least three letters): nov, Novem, novembre. */
    private fun monthOf(word: String, locale: Locale): Int? {
        val w = word.lowercase(locale)
        if (w.length < 3) return null
        for (l in listOf(Locale.ENGLISH, locale)) {
            for (month in Month.entries) {
                for (style in listOf(TextStyle.FULL, TextStyle.FULL_STANDALONE)) {
                    if (month.getDisplayName(style, l).lowercase(l).startsWith(w)) return month.value
                }
            }
        }
        return null
    }
}
