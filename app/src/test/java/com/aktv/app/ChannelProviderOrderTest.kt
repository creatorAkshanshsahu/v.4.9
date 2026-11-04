package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelProviderOrderTest {
    @Test fun `Sony YAY respects JJTV first even when a different device remembered TG`() {
        assertEquals(
            listOf(LiveProvider.JJTV, LiveProvider.TG, LiveProvider.JIOM3U),
            ChannelProviderOrder.order("872", v2 = false, rememberedWebsite = TgResolver.SITE)
        )
    }

    @Test fun `Sony Ten 3 HD tries JJTV first as requested and retains TG fallback`() {
        assertEquals(
            listOf(LiveProvider.JJTV, LiveProvider.TG, LiveProvider.JIOM3U),
            ChannelProviderOrder.order("892", v2 = false, rememberedWebsite = TgResolver.SITE)
        )
    }

    @Test fun `explicit TG selection still overrides Sony Ten 3 HD automatic preference`() {
        assertEquals(
            listOf(LiveProvider.TG, LiveProvider.JJTV, LiveProvider.JIOM3U),
            ChannelProviderOrder.order("892", v2 = false, rememberedWebsite = JjtvResolver.SITE,
                requested = LiveProvider.TG)
        )
    }

    @Test fun `explicit source selection overrides Sony YAY automatic preference`() {
        assertEquals(
            listOf(LiveProvider.TG, LiveProvider.JJTV, LiveProvider.JIOM3U),
            ChannelProviderOrder.order("872", v2 = false, rememberedWebsite = JjtvResolver.SITE,
                requested = LiveProvider.TG)
        )
    }

    @Test fun `manual JJTV choice stays pinned instead of silently switching the player to TG`() {
        assertEquals(listOf(LiveProvider.JJTV), ChannelProviderOrder.playbackOrder(
            "892", v2 = false, rememberedWebsite = TgResolver.SITE, requested = LiveProvider.JJTV))
        assertEquals(listOf(LiveProvider.TG), ChannelProviderOrder.playbackOrder(
            "872", v2 = false, rememberedWebsite = JjtvResolver.SITE, requested = LiveProvider.TG))
    }

    @Test fun `a playlist channel starts on the playlist provider and keeps the others as fallback`() {
        assertEquals(listOf(LiveProvider.JIOM3U, LiveProvider.JJTV, LiveProvider.TG),
            ChannelProviderOrder.playbackOrder("143", v2 = false, jio = true, rememberedWebsite = null))
    }

    @Test fun `a manually chosen provider stays pinned for a playlist channel too`() {
        assertEquals(listOf(LiveProvider.TG),
            ChannelProviderOrder.playbackOrder("143", v2 = false, jio = true, rememberedWebsite = JioM3uResolver.SITE,
                requested = LiveProvider.TG))
    }

    @Test fun `automatic Sony channel selection remains JJTV first with TG fallback`() {
        assertEquals(listOf(LiveProvider.JJTV, LiveProvider.TG, LiveProvider.JIOM3U), ChannelProviderOrder.playbackOrder(
            "892", v2 = false, rememberedWebsite = TgResolver.SITE))
    }

    @Test fun `a pinned Sony tile is played from its playlist and nothing else`() {
        assertEquals(listOf(LiveProvider.JIOM3U),
            ChannelProviderOrder.playbackOrder("892", v2 = false, jio = true,
                rememberedWebsite = TgResolver.SITE, pinned = true))
        assertEquals(listOf(LiveProvider.JIOM3U),
            ChannelProviderOrder.playbackOrder("162", v2 = false, jio = false,
                rememberedWebsite = JjtvResolver.SITE, pinned = true))
        // A manual provider choice still wins: the user is never overruled, they are just not silently moved.
        assertEquals(listOf(LiveProvider.TG),
            ChannelProviderOrder.playbackOrder("892", v2 = false, jio = true,
                rememberedWebsite = null, requested = LiveProvider.TG, pinned = true))
    }

    @Test fun `a page-only Sony tile has no native provider so its own player runs`() {
        assertEquals(emptyList<LiveProvider>(),
            ChannelProviderOrder.playbackOrder("page:sony-ten-4", v2 = false, jio = false,
                rememberedWebsite = TgResolver.SITE, pinned = true, pageOnly = true))
        // A tile the playlist carries still resolves natively; its page is only the fallback.
        assertEquals(listOf(LiveProvider.JIOM3U),
            ChannelProviderOrder.playbackOrder("892", v2 = false, jio = true,
                rememberedWebsite = null, pinned = true, pageOnly = false))
    }

    @Test fun `other V1 channels keep their remembered working provider first`() {
        assertEquals(
            listOf(LiveProvider.TG, LiveProvider.JJTV, LiveProvider.JIOM3U),
            ChannelProviderOrder.order("1108", v2 = false, rememberedWebsite = TgResolver.SITE)
        )
    }

    @Test fun `V2 IDs remain TG only regardless of channel ID or saved provider`() {
        assertEquals(
            listOf(LiveProvider.TG),
            ChannelProviderOrder.order("872", v2 = true, rememberedWebsite = JjtvResolver.SITE,
                requested = LiveProvider.JJTV)
        )
    }
}
