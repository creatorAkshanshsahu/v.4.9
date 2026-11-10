package com.aktv.app

/**
 * Which portal channels this app shows.
 *
 * A Stalker subscription carries every regional feed the reseller sells — Tamil, Telugu, Malayalam,
 * Kannada, Marathi, Bengali, Punjabi, Gujarati, Bhojpuri and more — and the viewer asked for English and
 * Hindi only. The decision is made from the channel's name and the genre the portal filed it under, and
 * the rule is deliberately conservative: an explicit English/Hindi marker keeps a channel even when
 * another language is also named, a different language drops it, and a channel with no language at all —
 * sport, movies, news, knowledge — is kept, because those are the rows worth having.
 */
internal object PortalLanguages {

    private val keep = listOf("english", "hindi")

    /** Languages the viewer does not want. Matching is on whole words, so "Sun TV" is not caught by "un". */
    private val drop = listOf(
        "tamil", "telugu", "malayalam", "malay", "kannada", "marathi", "bengali", "bangla", "punjabi",
        "gujarati", "urdu", "odia", "oriya", "assamese", "bhojpuri", "rajasthani", "haryanvi", "nepali",
        "sinhala", "sinhalese", "konkani", "kashmiri", "sanskrit", "manipuri", "mizo", "tulu",
        "arabic", "persian", "farsi", "hebrew", "turkish", "russian", "ukrainian", "chinese", "mandarin",
        "cantonese", "korean", "japanese", "thai", "vietnamese", "indonesian", "filipino", "tagalog",
        "french", "spanish", "portuguese", "german", "italian", "dutch", "greek", "polish", "romanian",
        "bulgarian", "serbian", "croatian", "bosnian", "albanian", "swedish", "norwegian", "danish",
        "finnish", "swahili", "afrikaans"
    )

    private val keepPattern = Regex("\\b(" + keep.joinToString("|") + ")\\b")
    private val dropPattern = Regex("\\b(" + drop.joinToString("|") + ")\\b")

    /** True when the channel belongs in the English/Hindi section. */
    fun keeps(name: String, genre: String = ""): Boolean {
        val text = "$name $genre".lowercase()
        if (keepPattern.containsMatchIn(text)) return true
        return !dropPattern.containsMatchIn(text)
    }
}
