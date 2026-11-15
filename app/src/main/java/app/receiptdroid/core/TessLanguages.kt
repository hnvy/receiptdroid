package app.receiptdroid.core

import java.util.Locale

/** Tesseract's language data: what a file's name says, what to call a language, and which are used. */
object TessLanguages {
    const val SUFFIX = ".traineddata"

    /** Orientation and script detection: Tesseract's, but it finds which way text runs and doesn't read it. */
    const val DETECTION = "osd"

    /**
     * The language code a file's name gives: deu.traineddata is deu, chi_sim.traineddata chi_sim. Null if
     * the name isn't a language file's, eg a download renamed or a file of another kind.
     */
    fun codeOf(fileName: String): String? {
        val name = fileName.substringAfterLast('/').trim()
        if (!name.endsWith(SUFFIX, ignoreCase = true)) return null
        val code = name.dropLast(SUFFIX.length)
        return code.takeIf { Regex("[A-Za-z][A-Za-z0-9_-]{0,31}").matches(it) }
    }

    private val SPECIAL = mapOf(
        "chi_sim" to "Chinese (Simplified)", "chi_tra" to "Chinese (Traditional)", "equ" to "Maths",
        "osd" to "Orientation and script", "enm" to "Middle English", "frm" to "Middle French",
    )
    private val VARIANTS = mapOf("vert" to "vertical", "frak" to "Fraktur", "old" to "old", "latn" to "Latin script", "cyrl" to "Cyrillic")

    /** What to call [code] in [locale]'s language: "German (deu)", or the code itself when it's not known. */
    fun displayName(code: String, locale: Locale = Locale.getDefault()): String {
        val parts = code.split('_')
        val base = SPECIAL[code] ?: SPECIAL[parts.take(2).joinToString("_")] ?: language(parts[0], locale) ?: return code
        val variants = parts.drop(if (code in SPECIAL || parts.take(2).joinToString("_") in SPECIAL) 2 else 1)
            .map { VARIANTS[it.lowercase()] ?: it }
        return (if (variants.isEmpty()) base else "$base, ${variants.joinToString(", ")}") + " ($code)"
    }

    /** A language by its three-letter code (Tesseract's: deu, fra, spa) or two-letter one. */
    private fun language(code: String, locale: Locale): String? {
        val lower = code.lowercase(Locale.ROOT)
        val match = when (lower.length) {
            2 -> Locale(lower)
            3 -> Locale.getAvailableLocales().firstOrNull { l -> runCatching { l.isO3Language }.getOrNull() == lower }
            else -> null
        } ?: return null
        val name = match.getDisplayLanguage(locale)
        return name.takeIf { it.isNotEmpty() && !it.equals(lower, ignoreCase = true) }?.replaceFirstChar { it.titlecase(locale) }
    }

    /** English first (receipts' labels and figures read best in it), then the rest by code. */
    fun ordered(codes: Collection<String>): List<String> = codes.distinct().sortedWith(compareBy<String> { if (it == "eng") 0 else 1 }.thenBy { it })

    /**
     * The languages to read in: those there are, but those switched off, in order. Never none while there
     * are any: with every one off, all of them.
     */
    fun inUse(available: Collection<String>, off: Set<String>): List<String> {
        val readable = available.filter { it != DETECTION }
        return ordered(readable.filter { it !in off }.ifEmpty { readable })
    }
}
