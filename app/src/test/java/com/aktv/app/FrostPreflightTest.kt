package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A 200 is not proof of video. TG OUT has served a seek-bar sprite sheet - a real `#EXTM3U` whose media
 * lines are `.jpg` thumbnails - and a page where a playlist should be. Both used to be kept, cost a
 * startup cycle, and made the source look like it was skipped instantly.
 */
class FrostPreflightTest {

    @Test fun `a playlist is alive`() {
        val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\n720/index.m3u8\n"
        assertEquals(FrostPreflight.Verdict.ALIVE, FrostPreflight.verdict(200, VodFormat.HLS, body, "application/x-mpegURL"))
    }

    @Test fun `a sprite sheet wearing a playlist is dead`() {
        val sprites = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            for (i in 0 until 8) append("#EXTINF:1.0,\n/720/000$i.jpg\n")
        }
        assertEquals(FrostPreflight.Verdict.DEAD, FrostPreflight.verdict(200, VodFormat.HLS, sprites))
    }

    @Test fun `a page where a playlist should be is dead, not unknown`() {
        assertEquals(FrostPreflight.Verdict.DEAD, FrostPreflight.verdict(200, VodFormat.HLS, "<!doctype html><html>login</html>"))
        assertEquals(FrostPreflight.Verdict.DEAD, FrostPreflight.verdict(200, VodFormat.HLS, "", "text/html; charset=utf-8"))
    }

    @Test fun `an answer that proves nothing keeps the source`() {
        // Keeping an unproven source is right: a CDN that dislikes the probe is still playable.
        assertEquals(FrostPreflight.Verdict.UNKNOWN, FrostPreflight.verdict(200, VodFormat.HLS, ""))
        assertEquals(FrostPreflight.Verdict.UNKNOWN, FrostPreflight.verdict(503, VodFormat.HLS, ""))
        assertEquals(FrostPreflight.Verdict.ALIVE, FrostPreflight.verdict(200, VodFormat.MP4, ""))
        assertEquals(FrostPreflight.Verdict.UNKNOWN, FrostPreflight.verdict(200, VodFormat.DASH, ""))
    }

    @Test fun `a refusal is dead`() {
        for (status in listOf(401, 403, 404, 410)) {
            assertEquals("HTTP $status", FrostPreflight.Verdict.DEAD, FrostPreflight.verdict(status, VodFormat.HLS, "#EXTM3U"))
        }
    }
}
