package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFreshnessTest {
    private fun source(expSeconds: Long?) = TgStream(
        "JJTV · Server 1",
        "https://media.example/bpk-tv/chan/live.mpd" + (expSeconds?.let { "?__hdnea__=exp=$it~hmac=fixture" } ?: ""),
        "dash"
    )

    private fun at(expSeconds: Long, remainingMs: Long) = expSeconds * 1000 - remainingMs

    @Test fun `a credential inside the phone margin is refreshed before playback`() {
        assertTrue(PlaybackFreshness.needsRefresh(listOf(source(1_000)), at(1_000, 20_000),
            PlaybackFreshness.marginMs(television = false)))
    }

    @Test fun `a phone margin that is fine before a slow TV start still refreshes on TV`() {
        val sources = listOf(source(1_000))
        val now = at(1_000, 45_000)
        assertFalse(PlaybackFreshness.needsRefresh(sources, now, PlaybackFreshness.marginMs(television = false)))
        assertTrue(PlaybackFreshness.needsRefresh(sources, now, PlaybackFreshness.marginMs(television = true)))
    }

    @Test fun `a comfortable credential is not refetched`() {
        assertFalse(PlaybackFreshness.needsRefresh(listOf(source(1_000)), at(1_000, 10 * 60_000),
            PlaybackFreshness.marginMs(television = true)))
    }

    @Test fun `opaque credentials are left to the provider instead of being guessed`() {
        assertFalse(PlaybackFreshness.needsRefresh(listOf(source(null)), 0, PlaybackFreshness.marginMs(true)))
        assertNull(PlaybackFreshness.remainingSeconds(listOf(source(null)), 0))
    }

    @Test fun `the earliest expiry across sources decides`() {
        val sources = listOf(source(1_000), source(5_000))
        assertTrue(PlaybackFreshness.needsRefresh(sources, at(1_000, 5_000), 60_000))
        assertEquals(5L, PlaybackFreshness.remainingSeconds(sources, at(1_000, 5_000)))
    }

    @Test fun `an empty source list never triggers a refetch`() {
        assertFalse(PlaybackFreshness.needsRefresh(emptyList(), 0, 60_000))
        assertNull(PlaybackFreshness.remainingSeconds(emptyList(), 0))
    }
}
