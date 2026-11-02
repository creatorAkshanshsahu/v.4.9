package com.aktv.app

/** Applies only request behavior observed in the live TG website's media fetches. */
internal object WebsiteRequestParity {
    /** Chromium's TG player fetches its Jio media without Referer; preserve Origin and all other metadata. */
    fun playbackSources(provider: LiveProvider, sources: List<TgStream>): List<TgStream> =
        if (provider == LiveProvider.TG) sources.map { source ->
            source.copy(
                headers = source.headers.filterKeys { key ->
                    !key.equals("Referer", ignoreCase = true) && !key.equals("Origin", ignoreCase = true)
                },
                omitReferrer = true
            )
        } else sources
}
