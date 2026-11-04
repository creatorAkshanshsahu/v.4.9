package com.aktv.app

/** Per-channel default based only on a user-confirmed native source; all alternatives remain available. */
internal object ChannelProviderOrder {
    /** Manual provider selection is pinned for this tune: never silently display the other provider. */
    fun playbackOrder(
        channelId: String,
        v2: Boolean,
        jio: Boolean,
        rememberedWebsite: String?,
        requested: LiveProvider? = null
    ): List<LiveProvider> = when {
        v2 -> listOf(LiveProvider.TG)
        jio -> requested?.let { listOf(it) } ?: listOf(LiveProvider.JIOM3U) +
            LiveProviders.order(v2, rememberedWebsite, null).filter { it != LiveProvider.JIOM3U }
        else -> requested?.let { listOf(it) } ?: order(channelId, v2, rememberedWebsite)
    }

    fun order(
        channelId: String,
        v2: Boolean,
        rememberedWebsite: String?,
        requested: LiveProvider? = null
    ): List<LiveProvider> {
        // Respect the user's JJTV-first preference for Sony YAY (872) and Sony Ten 3 HD (892).
        // Recent HTTP 403 reports do not establish that either provider is generally unavailable;
        // keep TG as native fallback. Explicit source choices still take priority.
        val remembered = if (!v2 && requested == null) when (channelId) {
            "872", "892" -> JjtvResolver.SITE
            else -> rememberedWebsite
        } else rememberedWebsite
        return LiveProviders.order(v2, remembered, requested)
    }
}
