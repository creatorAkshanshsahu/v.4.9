package com.aktv.app

/** Per-channel default based only on a user-confirmed native source; all alternatives remain available. */
internal object ChannelProviderOrder {
    fun order(
        channelId: String,
        v2: Boolean,
        rememberedWebsite: String?,
        requested: LiveProvider? = null
    ): List<LiveProvider> {
        // Sony YAY (V1 ID 872) has a confirmed JJTV/Media3 success on mobile. The user wants
        // JJTV tried first for Sony Ten 3 HD as well; TG remains available as the native fallback.
        // Explicit source choices still take priority over these automatic preferences.
        val remembered = if (!v2 && requested == null) when (channelId) {
            "872", "892" -> JjtvResolver.SITE
            else -> rememberedWebsite
        } else rememberedWebsite
        return LiveProviders.order(v2, remembered, requested)
    }
}
