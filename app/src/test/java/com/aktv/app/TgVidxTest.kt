package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TgVidxTest {
    @Test fun `movie embeds follow the documented contract`() {
        val title = FrostTitle(1303331, "movie")
        assertEquals("https://tgvidx.lovable.app/embed/movie/1303331?lang=hi", TgVidx.embed(title))
        assertEquals("https://tgvidx.lovable.app/embed/movie/1303331", TgVidx.embed(title, hindiFirst = false))
    }

    @Test fun `series embeds carry season and episode`() {
        val title = FrostTitle(94605, "tv", season = 1, episode = 2)
        assertEquals("https://tgvidx.lovable.app/embed/tv/94605/1/2?lang=hi", TgVidx.embed(title))
        assertEquals("https://tgvidx.lovable.app/embed/tv/94605/1/2", TgVidx.embed(title, hindiFirst = false))
    }
}
