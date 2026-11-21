package com.aktv.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The address decides the container. TG OUT has labelled an HLS master `type: "mp4"`; trusting that label
 * handed Media3 an MP4 extractor, which rejects a playlist on its first bytes - which is what "the stream
 * is found and then skipped instantly" looks like from the sofa.
 */
class FrostContainerLabelTest {

    private fun source(url: String, type: String) = JSONObject().put("sources",
        org.json.JSONArray().put(JSONObject().put("url", url).put("type", type).put("quality", "1080p")))

    private fun formatOf(url: String, type: String): VodFormat =
        FrostProtocol.parse(source(url, type).toString(), FrostProvider.OUT).single().format

    @Test fun `an hls address labelled mp4 is played as hls`() {
        assertEquals(VodFormat.HLS, formatOf("https://cdn.example.test/master.m3u8?token=1", "mp4"))
        assertEquals(VodFormat.HLS, formatOf("https://cdn.example.test/master.m3u?sig=2", "video"))
    }

    @Test fun `an mp4 address labelled hls is played as a file`() {
        assertEquals(VodFormat.MP4, formatOf("https://cdn.example.test/movie.mp4?sig=3", "hls"))
    }

    @Test fun `an address with no extension still follows its label`() {
        assertEquals(VodFormat.HLS, formatOf("https://cdn.example.test/hls/master?token=4", "hls"))
        assertEquals(VodFormat.MP4, formatOf("https://cdn.example.test/download?id=5", "mp4"))
        assertEquals(VodFormat.DASH, formatOf("https://cdn.example.test/manifest?id=6", "dash"))
    }

    @Test fun `the retried source keeps its address and only changes its container`() {
        val only = FrostProtocol.parse(source("https://cdn.example.test/master.m3u8", "mp4").toString(), FrostProvider.OUT).single()
        val other = only.withFormat(VodFormat.MP4)
        assertEquals(only.url, other.url)
        assertEquals(only.identity, other.identity)
        assertEquals(VodFormat.MP4, other.format)
        assertEquals(only.provider, other.provider)
    }
}
