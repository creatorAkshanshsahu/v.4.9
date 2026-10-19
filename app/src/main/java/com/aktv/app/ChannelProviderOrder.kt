package com.aktv.app

/** Per-channel default based only on a user-confirmed native source; all alternatives remain available. */
internal object ChannelProviderOrder {
    fun order(
        channelId: String,
        v2: Boolean,
        rememberedWebsite: String?,
        requested: LiveProvider? = null
    ): List<LiveProvider> {
        // Sony YAY (V1 ID 872) has a confirmed JJTV/Media3 success on mobile, while its recent
        // TV TG attempt reported no supported tracks. Try that observed working provider first
        // instead of letting an old TV-side TG preference delay it. Keep TG as automatic fallback.
        val remembered = if (!v2 && channelId == "872" && requested == null) JjtvResolver.SITE else rememberedWebsite
        return LiveProviders.order(v2, remembered, requested)
    }
}
