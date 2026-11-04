package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A playlist channel only plays when the player is given an address that actually answers.
 *
 * These cases pin the two things that previously stopped every `jtv1.mpd?id=…` channel: the playlist's
 * `#EXTHTTP` cookie (a Jio CDN token, not a cookie for the playlist host) was re-sent as a signed query
 * parameter to a host that had not minted it, and the player was pointed at a 302 instead of the CDN
 * address behind it, so a refusal surfaced as an opaque decode error.
 */
class JioM3uPlaybackTest {

    private val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="143" group-title="English" tvg-logo="https://logo.example/143.png",CNBC TV18 Prime
        #KODIPROP:inputstream.adaptive.license_type=clearkey
        #KODIPROP:inputstream.adaptive.license_key=https://allinonereborn2.online/jtv-fetch/keyid.php?id=143
        #EXTVLCOPT:http-user-agent=@allinone_reborn
        #EXTHTTP:{"cookie":"__hdnea__=st=1~exp=2~acl=/*~hmac=abc"}
        https://allinonereborn2.online/jiotv-m3u/jtv1.mpd?id=143
    """.trimIndent()

    private val keyDoc = """{"keys":[{"kty":"oct","k":"snl85eQD7iLY42xtXytmMA","kid":"6xkRPBHOXPuAyJaWdloBhw"}],"type":"temporary"}"""

    /** The key id the key document names, in the hex form an MPD writes. */
    private val kidHex = "EB19113C-11CE-5CFB-80C8-9696765A0187"

    private fun mpd(kid: String = kidHex) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
          <Period><AdaptationSet mimeType="video/mp4">
            <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" cenc:default_KID="$kid"/>
          </AdaptationSet></Period>
        </MPD>
    """.trimIndent()

    private val cdnUrl = "https://jiotvmblive.cdn.jio.com/bpk-tv/CNBCTV18Prime_MOB/WDVLive/index.mpd" +
        "?__hdnea__=st=9~exp=10~acl=/*~hmac=z"

    private fun resolver(media: (Int) -> TgResponse?): JioM3uResolver {
        var mediaCalls = 0
        return JioM3uResolver(http = { url, _ ->
            if (url.contains("keyid.php")) TgResponse(200, keyDoc.toByteArray())
            else if (url.contains(".mpd")) media(mediaCalls++) ?: TgResponse(403, ByteArray(0))
            else TgResponse(200, playlist.toByteArray(), "application/vnd.apple.mpegurl")
        })
    }

    @Test fun `the redirect target is handed to the player, not the redirecting URL`() {
        val stream = resolver { TgResponse(200, mpd().toByteArray(), "application/dash+xml", finalUrl = cdnUrl) }
            .resolve("143").single()
        assertEquals(cdnUrl, stream.url)
        // The media host gets exactly what an IPTV player sends for this link: the playlist's user agent
        // and its own cookie. No Origin, no Referer.
        assertEquals(
            mapOf("User-Agent" to "@allinone_reborn", "Cookie" to "__hdnea__=st=1~exp=2~acl=/*~hmac=abc"),
            stream.headers
        )
        assertTrue(stream.omitOrigin)
        assertEquals(JioM3uResolver.SITE, stream.requestOrigin)
        // The stream no longer carries the Jio CDN token as a cookie, so it cannot be re-sent as `__hdnea__`.
        assertEquals("", stream.cookie)
        assertEquals("dash", stream.type)
    }

    @Test fun `the playlist host still receives the browser-style headers the site uses`() {
        val entry = JioM3uProtocol.parse(playlist).single()
        val probe = JioM3uProtocol.requestHeaders(entry)
        assertEquals("@allinone_reborn", probe["User-Agent"])
        assertEquals(JioM3uResolver.SITE, probe["Origin"])
        assertEquals(JioM3uResolver.SITE + "/", probe["Referer"])
        // ... while the media host receives the user agent and the playlist's own cookie.
        assertEquals(
            mapOf("User-Agent" to "@allinone_reborn", "Cookie" to "__hdnea__=st=1~exp=2~acl=/*~hmac=abc"),
            JioM3uProtocol.mediaHeaders(entry)
        )
    }

    @Test fun `the first attempt at a channel carries no browser origin`() {
        val attempts: MutableList<Map<String, String>> = ArrayList()
        val resolver = JioM3uResolver(http = { url, headers ->
            when {
                url.contains("keyid.php") -> TgResponse(200, keyDoc.toByteArray())
                url.contains(".mpd") -> {
                    attempts.add(headers)
                    TgResponse(200, mpd().toByteArray(), "application/dash+xml", finalUrl = cdnUrl)
                }
                else -> TgResponse(200, playlist.toByteArray(), "application/vnd.apple.mpegurl")
            }
        })
        resolver.resolve("143")
        // The IPTV player's request: its user agent and its cookie, and no hotlink headers.
        assertEquals(setOf("User-Agent", "Cookie"), attempts.first().keys)
        assertEquals("@allinone_reborn", attempts.first()["User-Agent"])
    }

    @Test fun `a refused request is retried with the playlist's own cookie`() {
        val stream = resolver { call ->
            if (call == 0) TgResponse(403, ByteArray(0)) else TgResponse(200, mpd().toByteArray(), finalUrl = cdnUrl)
        }.resolve("143").single()
        assertEquals(cdnUrl, stream.url)
    }

    @Test fun `when nothing answers the advertised URL is kept and the refusal is visible`() {
        val stream = resolver { TgResponse(403, ByteArray(0)) }.resolve("143").single()
        assertEquals("https://allinonereborn2.online/jiotv-m3u/jtv1.mpd?id=143", stream.url)
        assertTrue(stream.label.contains("HTTP 403"))
        // A probe orders sources; it must never remove one.
        assertNotNull(stream.drm)
    }

    @Test fun `the licence names the key id the manifest uses`() {
        val stream = resolver { TgResponse(200, mpd().toByteArray(), finalUrl = cdnUrl) }.resolve("143").single()
        val pairs = ClearKey.pairs(stream.drm!!)
        assertEquals(setOf("6xkRPBHOXPuAyJaWdloBhw"), pairs.keys)
        assertEquals("snl85eQD7iLY42xtXytmMA", pairs.values.single())
    }

    @Test fun `a manifest key id that disagrees relabels the provider key instead of dropping it`() {
        val stream = resolver { TgResponse(200, mpd("AABBCCDD-EEFF-0011-2233-445566778899").toByteArray(), finalUrl = cdnUrl) }
            .resolve("143").single()
        val pairs = ClearKey.pairs(stream.drm!!)
        assertEquals(setOf("qrvM3e7_ABEiM0RVZneImQ"), pairs.keys)
        assertEquals("snl85eQD7iLY42xtXytmMA", pairs.values.single())
        assertNotNull(ClearKey.license(stream.drm!!))
    }

    @Test fun `an HTML error page is not accepted as a manifest`() {
        assertFalse(JioM3uProtocol.isManifest("dash", "<!doctype html><html><body>Forbidden</body></html>"))
        assertTrue(JioM3uProtocol.isManifest("dash", mpd()))
        assertTrue(JioM3uProtocol.isManifest("hls", "#EXTM3U\n#EXT-X-VERSION:3"))
        assertFalse(JioM3uProtocol.isManifest("hls", "{\"error\":\"expired\"}"))
    }

    @Test fun `manifest key ids are read in each spelling the MPD allows`() {
        assertEquals(setOf("6xkRPBHOXPuAyJaWdloBhw"), JioM3uProtocol.defaultKids(mpd()))
        assertEquals(setOf("6xkRPBHOXPuAyJaWdloBhw"),
            JioM3uProtocol.defaultKids("""<ContentProtection cenc:default_KID="eb19113c11ce5cfb80c89696765a0187"/>"""))
        assertTrue(JioM3uProtocol.defaultKids("<MPD/>").isEmpty())
    }
}
