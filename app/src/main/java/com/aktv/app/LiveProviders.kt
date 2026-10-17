package com.aktv.app

import java.security.MessageDigest

internal enum class LiveProvider(val website: String) {
    TG(TgResolver.SITE),
    JJTV(JjtvResolver.SITE)
}

/** Provider order is metadata only. There is still exactly one native playback engine. */
internal object LiveProviders {
    fun order(v2: Boolean, rememberedWebsite: String?, requested: LiveProvider? = null): List<LiveProvider> {
        if (v2) return listOf(LiveProvider.TG) // V2's numeric IDs are not Jio channel IDs.
        // A working TG channel says nothing about a different, encrypted JJTV channel. Do not let
        // the old global last-provider hint starve JJTV on a fresh TV installation.
        val preferred = requested ?: LiveProvider.values().firstOrNull { it.website == rememberedWebsite } ?: LiveProvider.JJTV
        return listOf(preferred) + LiveProvider.values().filter { it != preferred }
    }

    fun provider(source: TgStream) = if (source.requestOrigin == JjtvResolver.SITE) LiveProvider.JJTV else LiveProvider.TG

    fun sourceKey(source: TgStream): String = MessageDigest.getInstance("SHA-256")
        .digest("${source.requestOrigin}\n${source.label}\n${source.url.substringBefore('?')}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
