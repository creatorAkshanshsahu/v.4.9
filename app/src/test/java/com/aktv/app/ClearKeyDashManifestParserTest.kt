package com.aktv.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.extractor.mp4.PsshAtomUtil
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/** Actual Media3 parsing under an Android runtime, not a second JSON-only implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClearKeyDashManifestParserTest {
    private val first = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    private val second = UUID.fromString("10213243-5465-7687-98a9-bacbdcedfe0f")
    private val drm = JSONObject("""{"type":"clearkey","keys":[
        {"keyId":"$first","key":"${"a".repeat(32)}"},
        {"keyId":"$second","key":"${"b".repeat(32)}"}]}""")

    private fun protections(scheme: String = "cenc"): String {
        val pssh = Base64.getEncoder().encodeToString(PsshAtomUtil.buildPsshAtom(C.WIDEVINE_UUID, byteArrayOf(8, 1)))
        return """<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="$scheme"/>
            <ContentProtection schemeIdUri="urn:uuid:${C.WIDEVINE_UUID}"><cenc:pssh>$pssh</cenc:pssh></ContentProtection>"""
    }

    private fun document(protection: String): String = """<?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013"
             type="static" mediaPresentationDuration="PT12S" minBufferTime="PT1S">
          <Period id="p" start="PT0S">
            <AdaptationSet contentType="video" mimeType="video/mp4">$protection
              <Representation id="v" bandwidth="1000000" codecs="avc1.42c01e" width="1280" height="720">
                <BaseURL>video/</BaseURL>
                <SegmentTemplate timescale="1" duration="2" startNumber="1" initialization="init.m4s" media="${'$'}Number${'$'}.m4s"/>
              </Representation>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="hi">$protection
              <Representation id="a" bandwidth="128000" codecs="mp4a.40.2" audioSamplingRate="48000">
                <BaseURL>audio/</BaseURL>
                <SegmentTemplate timescale="1" duration="2" startNumber="1" initialization="init.m4s" media="${'$'}Number${'$'}.m4s"/>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>""".trimIndent()

    private fun parse(parser: DashManifestParser, protection: String): DashManifest = parser.parse(
        Uri.parse("https://media.example/live/index.mpd"), ByteArrayInputStream(document(protection).toByteArray()))
    private fun schemes(manifest: DashManifest) = manifest.getPeriod(0).adaptationSets.map {
        requireNotNull(it.representations.single().format.drmInitData)
    }
    private fun clearKeyCompatible(drm: DrmInitData) = (0 until drm.schemeDataCount).any {
        drm[it].matches(C.COMMON_PSSH_UUID) || drm[it].matches(C.CLEARKEY_UUID)
    }
    private fun keyIds(data: ByteArray): List<UUID> {
        assertEquals(C.COMMON_PSSH_UUID, PsshAtomUtil.parseUuid(data))
        val buffer = ByteBuffer.wrap(data)
        buffer.position(28)
        return List(buffer.int) { UUID(buffer.long, buffer.long) }
    }

    @Test fun `default Media3 parsing leaves a Widevine-only MPD unusable by a ClearKey manager`() {
        val parsed = schemes(parse(DashManifestParser(), protections()))
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { !clearKeyCompatible(it) })
    }

    @Test fun `explicit ClearKeys give both audio and video usable common PSSH with every supplied id`() {
        val parsed = schemes(parse(ClearKeyDashManifestParser(drm), protections()))
        assertEquals(2, parsed.size)
        parsed.forEach { data ->
            assertTrue(clearKeyCompatible(data))
            assertEquals("cenc", data.schemeType)
            assertEquals(1, data.schemeDataCount)
            assertEquals(listOf(first, second), keyIds(requireNotNull(data[0].data)))
        }
    }

    @Test fun `provided ClearKeys also initialize tracks when the MPD omits DRM declarations`() {
        val plain = parse(DashManifestParser(), "")
        assertNull(plain.getPeriod(0).adaptationSets.first().representations.single().format.drmInitData)
        assertTrue(schemes(parse(ClearKeyDashManifestParser(drm), "")).all(::clearKeyCompatible))
    }

    @Test fun `the declared encryption scheme and track metadata are not rewritten`() {
        val parsed = parse(ClearKeyDashManifestParser(drm), protections("cbcs"))
        assertTrue(schemes(parsed).all { it.schemeType == "cbcs" })
        val video = parsed.getPeriod(0).adaptationSets[0].representations.single()
        val audio = parsed.getPeriod(0).adaptationSets[1].representations.single()
        assertEquals(1280, video.format.width)
        assertEquals(720, video.format.height)
        assertEquals(MimeTypes.VIDEO_H264, video.format.sampleMimeType)
        assertEquals("hi", audio.format.language)
        assertEquals(MimeTypes.AUDIO_AAC, audio.format.sampleMimeType)
        assertEquals(12000L, parsed.durationMs)
        assertTrue(video.baseUrls[0].url.endsWith("/live/video/"))
    }

    @Test(expected = IllegalArgumentException::class) fun `no configured keys means no manufactured DRM initialization`() {
        ClearKeyDashManifestParser(JSONObject("""{"type":"clearkey","keys":[]}"""))
    }

    @Test(expected = IllegalArgumentException::class) fun `a Widevine licence is not silently turned into ClearKey`() {
        ClearKeyDashManifestParser(JSONObject("""{"type":"widevine","licenseUrl":"https://license.example/"}"""))
    }
}
