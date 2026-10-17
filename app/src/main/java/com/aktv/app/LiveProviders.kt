package com.aktv.app

import java.security.MessageDigest

internal enum class LiveProvider(val website: String) {
    TG(TgResolver.SITE),
    JJTV(JjtvResolver.SITE)
}

/** Provider order is metadata only. There is still exactly one native playback engine. */
internal object LiveProviders {
    fun order(v2: Boolean, rememberedWebsite: String?): List<LiveProvider> = when {
        v2 -> listOf(LiveProvider.TG) // V2's numeric IDs are not Jio channel IDs.
        rememberedWebsite == LiveProvider.JJTV.website -> listOf(LiveProvider.JJTV, LiveProvider.TG)
        else -> listOf(LiveProvider.TG, LiveProvider.JJTV)
    }

    fun sourceKey(source: TgStream): String = MessageDigest.getInstance("SHA-256")
        .digest("${source.requestOrigin}\n${source.label}\n${source.url.substringBefore('?')}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
