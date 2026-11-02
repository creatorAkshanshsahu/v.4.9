package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelProviderOrderTest {
    @Test fun `Sony YAY respects JJTV first even when a different device remembered TG`() {
        assertEquals(
            listOf(LiveProvider.JJTV, LiveProvider.TG),
            ChannelProviderOrder.order("872", v2 = false, rememberedWebsite = TgResolver.SITE)
        )
    }

    @Test fun `Sony Ten 3 HD tries JJTV first as requested and retains TG fallback`() {
        assertEquals(
            listOf(LiveProvider.JJTV, LiveProvider.TG),
            ChannelProviderOrder.order("892", v2 = false, rememberedWebsite = TgResolver.SITE)
        )
    }

    @Test fun `explicit TG selection still overrides Sony Ten 3 HD automatic preference`() {
        assertEquals(
            listOf(LiveProvider.TG, LiveProvider.JJTV),
            ChannelProviderOrder.order("892", v2 = false, rememberedWebsite = JjtvResolver.SITE,
                requested = LiveProvider.TG)
        )
    }

    @Test fun `explicit source selection overrides Sony YAY automatic preference`() {
        assertEquals(
            listOf(LiveProvider.TG, LiveProvider.JJTV),
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

    @Test fun `automatic Sony channel selection remains JJTV first with TG fallback`() {
        assertEquals(listOf(LiveProvider.JJTV, LiveProvider.TG), ChannelProviderOrder.playbackOrder(
            "892", v2 = false, rememberedWebsite = TgResolver.SITE))
    }

    @Test fun `other V1 channels keep their remembered working provider first`() {
        assertEquals(
            listOf(LiveProvider.TG, LiveProvider.JJTV),
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
