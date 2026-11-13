package com.aktv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The maxTV shelf is English and Hindi only, with sport, movies and news kept whatever their label. */
class PortalLanguagesTest {

    @Test
    fun `english and hindi channels stay`() {
        assertTrue(PortalLanguages.keeps("Star Sports 1 Hindi", "SPORTS"))
        assertTrue(PortalLanguages.keeps("Sony SAB", "HINDI"))
        assertTrue(PortalLanguages.keeps("English Movies", ""))
        assertTrue(PortalLanguages.keeps("Zee Cinema", "HINDI MOVIES"))
        assertTrue(PortalLanguages.keeps("BBC Earth", "KNOWLEDGE"))
    }

    @Test
    fun `regional feeds are dropped`() {
        assertFalse(PortalLanguages.keeps("Sun TV", "TAMIL"))
        assertFalse(PortalLanguages.keeps("Star Sports 1 Tamil", "SPORTS"))
        assertFalse(PortalLanguages.keeps("Zee Telugu HD", ""))
        assertFalse(PortalLanguages.keeps("Asianet", "MALAYALAM"))
        assertFalse(PortalLanguages.keeps("Colors Kannada", ""))
        assertFalse(PortalLanguages.keeps("Star Pravah", "MARATHI"))
        assertFalse(PortalLanguages.keeps("Zee Bangla", "BENGALI"))
        assertFalse(PortalLanguages.keeps("PTC Punjabi", ""))
        assertFalse(PortalLanguages.keeps("Bhojpuri Cinema", "BHOJPURI"))
    }

    @Test
    fun `a channel with no language at all is kept`() {
        assertTrue(PortalLanguages.keeps("Star Sports 1", "SPORTS"))
        assertTrue(PortalLanguages.keeps("Discovery Channel", ""))
        assertTrue(PortalLanguages.keeps("Sony Max", "MOVIES"))
    }

    @Test
    fun `an explicit english or hindi marker wins over another language in the same line`() {
        assertTrue(PortalLanguages.keeps("Star Gold", "HINDI/TAMIL"))
        assertTrue(PortalLanguages.keeps("Cartoon Network", "ENGLISH, TELUGU"))
    }

    @Test
    fun `jiotv playlist filters out assamese telugu tamil gujarati marathi and mislabelled regional feeds`() {
        val sampleM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="143" group-title="English" tvg-logo="https://logo/143.png",CNBC TV18 Prime
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=143
            #EXTINF:-1 tvg-id="156" group-title="Movies" tvg-logo="https://logo/156.png",Star Gold HD
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=156
            #EXTINF:-1 tvg-id="901" group-title="English" tvg-logo="https://logo/901.png",Lok manch Marathi
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=901
            #EXTINF:-1 tvg-id="902" group-title="English" tvg-logo="https://logo/902.png",Malyalam Naaptol
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=902
            #EXTINF:-1 tvg-id="903" group-title="Assamese" tvg-logo="https://logo/903.png",DY 365
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=903
            #EXTINF:-1 tvg-id="904" group-title="Telugu" tvg-logo="https://logo/904.png",ETV Telugu
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=904
            #EXTINF:-1 tvg-id="905" group-title="Tamil" tvg-logo="https://logo/905.png",Puthiya Thalaimurai
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=905
            #EXTINF:-1 tvg-id="906" group-title="Gujarati" tvg-logo="https://logo/906.png",TV9 Gujarati
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=906
            #EXTINF:-1 tvg-id="907" group-title="Marathi" tvg-logo="https://logo/907.png",Shemaroo MarathiBana
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=907
            #EXTINF:-1 tvg-id="908" group-title="Unknown" tvg-logo="https://logo/908.png",Animal Planet HD Telugu
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=908
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100
            #EXTINF:-1 tvg-id="1126" tvg-language="Tamil" group-title="SunNxt",KTV HD
            https://allinonereborn2.online/jiotv-m3u/jtv2.mpd?id=1126
        """.trimIndent()

        val entries = JioM3uProtocol.parse(sampleM3u)
        val sunNxt = entries.first { it.id == "1126" }
        org.junit.Assert.assertEquals("Tamil", sunNxt.language)
        org.junit.Assert.assertEquals("clearkey", sunNxt.licenseType)

        val kept = entries.filter { PortalLanguages.keepsJio(it) }.map { it.name }
        org.junit.Assert.assertEquals(listOf("CNBC TV18 Prime", "Star Gold HD"), kept)
    }
}
