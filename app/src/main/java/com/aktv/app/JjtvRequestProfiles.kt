package com.aktv.app

/**
 * Observed in Chrome while TG embeds /pind: the browser ignores the script's User-Agent/Referer
 * assignments and the iframe's no-referrer policy suppresses Referer entirely. These are two known
 * request styles for the SAME supplied source, not guessed servers or another player engine.
 */
internal object JjtvRequestProfiles {
    fun variants(source: TgStream): List<TgStream> {
        if (source.requestOrigin != JjtvResolver.SITE) return listOf(source)
        val browserHeaders = source.headers.filterKeys {
            !it.equals("User-Agent", true) && !it.equals("Referer", true) && !it.equals("Origin", true)
        } + ("Origin" to source.requestOrigin)
        val browser = source.copy(label = "${source.label} · browser request", headers = browserHeaders, omitReferrer = true)
        val native = source.copy(label = "${source.label} · native request")
        // Retain the declared native profile for channels/devices already working with it.
        return listOf(browser, native)
    }
}
