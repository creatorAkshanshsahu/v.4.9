package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelProviderOrderTest {
    @Test fun `Sony YAY prefers its phone-confirmed JJTV source over a remembered TV TG source`() {
        assertEquals(
            listOf(LiveProvider.JJTV, LiveProvider.TG),
            ChannelProviderOrder.order("872", v2 = false, rememberedWebsite = TgResolver.SITE)
        )
    }

    @Test fun `explicit source selection overrides Sony YAY automatic preference`() {
        assertEquals(
            listOf(LiveProvider.TG, LiveProvider.JJTV),
            ChannelProviderOrder.order("872", v2 = false, rememberedWebsite = JjtvResolver.SITE,
                requested = LiveProvider.TG)
        )
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
