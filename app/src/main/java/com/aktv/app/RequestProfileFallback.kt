package com.aktv.app

/**
 * JJTV advertises the same channel through two documented request styles: the browser style the
 * site's iframe actually produces (no Referer) and the declared native style. They are the same
 * provider, channel, URL and keys — only the request headers differ.
 *
 * A provider that refuses one style for a particular client may still accept the other, so after a
 * refusal the app should spend its next attempt on the *other* style instead of repeating the one
 * that was just rejected. This never invents a source, header or credential; when only one style is
 * advertised the previous behaviour is kept.
 */
internal object RequestProfileFallback {
    /** Index of the first source from the same provider that uses the other request style. */
    fun alternative(sources: List<TgStream>, refused: TgStream?): Int {
        if (refused == null) return -1
        return sources.indexOfFirst {
            it.requestOrigin == refused.requestOrigin && it.omitReferrer != refused.omitReferrer
        }
    }

    /** Index to try next: the other style when one exists, otherwise the previous source identity. */
    fun preferredIndex(sources: List<TgStream>, refused: TgStream?, sameAsBefore: (TgStream) -> Boolean): Int {
        alternative(sources, refused).takeIf { it >= 0 }?.let { return it }
        return sources.indexOfFirst(sameAsBefore).coerceAtLeast(0)
    }
}
