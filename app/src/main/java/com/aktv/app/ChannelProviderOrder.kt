package com.aktv.app

/** Per-channel default based only on a user-confirmed native source; all alternatives remain available. */
internal object ChannelProviderOrder {
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
