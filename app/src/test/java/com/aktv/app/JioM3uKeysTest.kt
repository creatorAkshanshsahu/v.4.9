package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allinonereborn2.online playlist hands most channels a key **document** URL instead of inline keys.
 * Without resolving it the native player had no ClearKey material at all, so those channels could only
 * play on the website. These cases lock the document contract (shape observed on the live playlist).
 */
class JioM3uKeysTest {
    private val keyDoc = """{"keys":[{"kty":"oct","k":"snl85eQD7iLY42xtXytmMA","kid":"6xkRPBHOXPuAyJaWdloBhw"}],"type":"temporary"}"""

    private fun entry(
        url: String = "https://allinonereborn2.online/jiotv-m3u/jtv1.mpd?id=143",
        licenseKey: String? = "https://allinonereborn2.online/jtv-fetch/keyid.php?id=143",
        cookie: String? = "__hdnea__=st=1~exp=2~acl=/*~hmac=abc"
    ) = JioM3uProtocol.Entry("143", "CNBC TV18 Prime", "English", "https://logo.example/143.png", url,
        licenseKey, "clearkey", "@allinone_reborn", cookie)

    @Test fun `the provider key document yields a usable ClearKey pair`() {
        val keys = JioM3uProtocol.parseKeys(keyDoc)
        assertEquals(mapOf("6xkRPBHOXPuAyJaWdloBhw" to "snl85eQD7iLY42xtXytmMA"), keys)
    }

    @Test fun `object style and plain key id pairs are accepted`() {
        assertEquals(mapOf("6xkRPBHOXPuAyJaWdloBhw" to "snl85eQD7iLY42xtXytmMA"),
            JioM3uProtocol.parseKeys("""{"keys":{"6xkRPBHOXPuAyJaWdloBhw":"snl85eQD7iLY42xtXytmMA"}}"""))
        assertEquals(mapOf("6xkRPBHOXPuAyJaWdloBhw" to "snl85eQD7iLY42xtXytmMA"),
            JioM3uProtocol.parseKeys("6xkRPBHOXPuAyJaWdloBhw:snl85eQD7iLY42xtXytmMA"))
    }

    @Test fun `unusable key material is ignored instead of guessed`() {
        assertTrue(JioM3uProtocol.parseKeys("").isEmpty())
        assertTrue(JioM3uProtocol.parseKeys("not-a-key-document").isEmpty())
        assertTrue(JioM3uProtocol.parseKeys("""{"keys":[{"kid":"short","k":"short"}]}""").isEmpty())
        assertTrue(JioM3uProtocol.parseKeys("""{"error":"expired"}""").isEmpty())
    }

    @Test fun `a resolved key document finally gives the channel local DRM`() {
        val drm = JioM3uProtocol.buildDrm(entry(), JioM3uProtocol.parseKeys(keyDoc))
        assertNotNull(drm)
        assertEquals("clearkey", drm!!.optString("type"))
        assertEquals(1, drm.optJSONArray("keys").length())
        assertEquals("https://allinonereborn2.online/jtv-fetch/keyid.php?id=143", drm.optString("licenseUrl"))
        // This is the check that previously threw "did not provide usable DRM" and stopped playback.
        assertNotNull(ClearKey.license(drm))
    }

    @Test fun `an unresolved key document still reaches the player as a key endpoint`() {
        val drm = JioM3uProtocol.buildDrm(entry())
        assertNotNull(drm)
        assertEquals("clearkey", drm!!.optString("type"))
        assertNull(drm.optJSONArray("keys"))
        assertEquals("https://allinonereborn2.online/jtv-fetch/keyid.php?id=143", drm.optString("licenseUrl"))
    }

    @Test fun `only the playlist's own host may supply keys`() {
        assertNull(JioM3uProtocol.licenseUrl(entry(licenseKey = "https://elsewhere.example/keyid.php?id=143")))
        assertNull(JioM3uProtocol.licenseUrl(entry(licenseKey = "http://allinonereborn2.online/keyid.php?id=143")))
        assertNull(JioM3uProtocol.buildDrm(entry(licenseKey = "https://elsewhere.example/keyid.php?id=143")))
        assertEquals("https://allinonereborn2.online/jtv-fetch/keyid.php?id=143",
            JioM3uProtocol.licenseUrl(entry()))
    }

    @Test fun `inline playlist keys keep working for the SunNxt variant`() {
        val drm = JioM3uProtocol.buildDrm(entry(licenseKey = "6xkRPBHOXPuAyJaWdloBhw:snl85eQD7iLY42xtXytmMA"))!!
        assertNotNull(ClearKey.license(drm))
        assertNull(drm.optString("licenseUrl").takeIf { it.isNotEmpty() })
    }

    @Test fun `the stream keeps the playlist cookie and browser headers`() {
        val stream = JioM3uProtocol.toTgStream(entry(), JioM3uProtocol.parseKeys(keyDoc))
        assertEquals("dash", stream.type)
        assertEquals(JioM3uResolver.SITE, stream.requestOrigin)
        assertEquals("@allinone_reborn", stream.headers["User-Agent"])
        assertEquals("__hdnea__=st=1~exp=2~acl=/*~hmac=abc", stream.cookie)
        assertNotNull(stream.drm)
    }

    @Test fun `an HLS entry is still typed as HLS`() {
        val stream = JioM3uProtocol.toTgStream(entry(url = "https://allinonereborn2.online/hls/channel.m3u8", licenseKey = null))
        assertEquals("hls", stream.type)
        assertNull(stream.drm)
    }

    @Test fun `a key document is not mistaken for a Widevine licence`() {
        val drm = JioM3uProtocol.buildDrm(entry(), JioM3uProtocol.parseKeys(keyDoc))!!
        assertEquals("clearkey", drm.getString("type"))
        assertTrue(drm.getString("licenseUrl").startsWith("https://allinonereborn2.online/"))
    }
}
