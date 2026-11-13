package com.aktv.app

/**
 * Which live channels belong in English & Hindi sections (JioTV and Stalker portals).
 *
 * Regional feeds — Assamese, Telugu, Tamil, Gujarati, Marathi, Malayalam, Kannada, Bengali,
 * Odia, Punjabi, Bhojpuri, Urdu and foreign-language feeds — are excluded so only English and
 * Hindi channels remain.
 *
 * Precedence:
 *  1. If the channel's own [name] names a regional/non-English/non-Hindi language (and does not
 *     itself explicitly name English or Hindi), it is dropped even when `group-title` was
 *     mislabelled `"English"` or `"Hindi"` in the upstream M3U (for example `"Lok manch Marathi"`
 *     or `"Malyalam Naaptol"` inside `group-title="English"`).
 *  2. If [language] (`tvg-language`) names a non-English/non-Hindi language without English/Hindi,
 *     it is dropped (catches `SunNxt` regional feeds).
 *  3. If [genre] (`group-title`) names a non-English/non-Hindi language without also naming
 *     English or Hindi, it is dropped; multi-audio tags like `"HINDI/TAMIL"` or
 *     `"ENGLISH, TELUGU"` on neutral channel names (`"Star Gold"`, `"Cartoon Network"`) stay kept.
 */
internal object PortalLanguages {

    private val keep = listOf("english", "hindi")

    /** Languages and regional-only network markers to exclude (matched on word boundaries). */
    private val drop = listOf(
        // Indian regional languages + common M3U misspellings
        "tamil", "tamizh",
        "telugu", "telgu",
        "malayalam", "malyalam", "malay", "keralam",
        "kannada",
        "marathi", "marathibana",
        "bengali", "bangla",
        "punjabi",
        "gujarati", "gujrati",
        "urdu",
        "odia", "oriya",
        "assamese", "assam", "asomiya", "axomiya",
        "bhojpuri", "rajasthani", "haryanvi", "chhattisgarhi", "maithili", "sindhi",
        "dogri", "santali", "bodo", "garo", "khasi", "nagamese", "kokborok",
        "nepali", "sinhala", "sinhalese", "konkani", "kashmiri", "sanskrit", "manipuri", "mizo", "tulu",
        // Regional-only groups & networks that sometimes appear under generic or Unknown categories
        "sunnxt", "sun tv", "ktv", "adithya", "udaya",
        "star maa", "star vijay", "vijay super", "star jalsha", "jalsha",
        "star suvarna", "suvarna", "star pravah", "pravah",
        "asianet", "mazhavil", "kairali", "mathrubhumi",
        "kalaignar", "polimer", "thanthi", "puthiya",
        "rengoni", "ramdhenu", "jonack", "pratidin", "dy365", "dy 365",
        "abp majha", "abp ananda", "abp asmita",
        "zee talkies", "zee picchar", "zee sarthak", "zee 24 taas", "zee 24 kalak", "zee 24 ghanta",
        "garvi gujarat", "jai maharashtra", "pudhari",
        // Foreign non-English languages
        "arabic", "persian", "farsi", "hebrew", "turkish", "russian", "ukrainian", "chinese", "mandarin",
        "cantonese", "korean", "japanese", "thai", "vietnamese", "indonesian", "filipino", "tagalog",
        "french", "spanish", "portuguese", "german", "italian", "dutch", "greek", "polish", "romanian",
        "bulgarian", "serbian", "croatian", "bosnian", "albanian", "swedish", "norwegian", "danish",
        "finnish", "swahili", "afrikaans"
    )

    private val keepPattern = Regex("\\b(" + keep.joinToString("|") + ")\\b")
    private val dropPattern = Regex("\\b(" + drop.joinToString("|") { Regex.escape(it) } + ")\\b")

    /** True when the channel belongs in an English & Hindi only catalogue. */
    fun keeps(name: String, genre: String = "", language: String = ""): Boolean {
        val nameLower = normalize(name)
        if (dropPattern.containsMatchIn(nameLower) && !keepPattern.containsMatchIn(nameLower)) {
            return false
        }
        val langLower = normalize(language)
        if (langLower.isNotEmpty() && dropPattern.containsMatchIn(langLower) && !keepPattern.containsMatchIn(langLower)) {
            return false
        }
        val genreLower = normalize(genre)
        if (genreLower.isNotEmpty() && dropPattern.containsMatchIn(genreLower) && !keepPattern.containsMatchIn(genreLower)) {
            return false
        }
        return true
    }

    /** True when a parsed JioTV M3U entry is an English or Hindi channel. */
    fun keepsJio(entry: JioM3uProtocol.Entry): Boolean =
        keeps(entry.name, entry.category, entry.language)

    private fun normalize(value: String): String =
        value.lowercase().replace('_', ' ').replace(Regex("\\s+"), " ").trim()
}
