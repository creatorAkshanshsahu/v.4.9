package com.aktv.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.RangedUri
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/** Opt-in real source/manifest/byte-range checks, NOT Android hardware playback tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FrostLiveContractTest {
    @Test fun `current Frost movie source is parseable by Media3`() = check(FrostTitle(1288069, "movie"))
    @Test fun `current Frost episode source is parseable by Media3`() = check(FrostTitle(213562, "tv", 1, 1))

    private fun check(title: FrostTitle) {
        assumeTrue(System.getProperty("aktv.live.frost") == "true")
        val report = JSONObject().put("type", title.type).put("id", title.id).put("season", title.season).put("episode", title.episode)
            .put("decodedPlaybackTested", false)
        try {
            val sources = FrostResolver().resolve(title)
            report.put("sources", sources.size).put("formats", JSONArray(sources.map { it.format.name }.distinct()))
                .put("subtitleCount", sources.first().subtitles.size)
            val source = sources.firstOrNull { it.format == VodFormat.DASH }
            assertNotNull("No DASH source returned for the sampled title", source)
            source!!
            val client = FrostHttp.client(source).newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(source.url).build()).execute().use { response ->
                report.put("manifestStatus", response.code)
                assertTrue("DASH metadata unavailable (HTTP ${response.code})", response.isSuccessful)
                val bytes = FrostProtocol.readBounded(response.body!!.byteStream(), 512 * 1024)
                val manifest = DashManifestParser().parse(Uri.parse(source.url), ByteArrayInputStream(bytes))
                report.put("media3Parsed", true).put("periods", manifest.periodCount).put("manifestBytes", bytes.size)
                val video = manifest.getPeriod(0).adaptationSets.first { it.type == C.TRACK_TYPE_VIDEO }.representations.first()
                report.put("videoMime", video.format.sampleMimeType).put("width", video.format.width).put("height", video.format.height)
                    .put("manifestDrmPresent", video.format.drmInitData != null)
                val base = video.baseUrls.first().url
                val probes = JSONArray()
                fun probe(part: RangedUri?, kind: String) {
                    if (part == null) return
                    val url = FrostProtocol.mediaUrl(part.resolveUriString(base)) ?: return
                    val start = part.start.coerceAtLeast(0)
                    val count = if (part.length > 0) minOf(part.length, 4096) else 4096
                    val item = JSONObject().put("kind", kind)
                    try {
                        client.newCall(Request.Builder().url(url).header("Range", "bytes=$start-${start + count - 1}").build()).execute().use { media ->
                            item.put("status", media.code).put("type", media.header("Content-Type").orEmpty().substringBefore(';'))
                            // Read only a prefix even if a CDN ignores Range; response close cancels the rest.
                            val prefix = ByteArray(4096)
                            item.put("prefixBytes", media.body?.byteStream()?.read(prefix)?.coerceAtLeast(0) ?: 0)
                        }
                    } catch (error: Exception) { item.put("error", error.javaClass.simpleName) }
                    probes.put(item)
                }
                probe(video.initializationUri, "initialization")
                video.index?.let { probe(it.getSegmentUrl(it.firstSegmentNum), "media") }
                report.put("byteRangeProbes", probes)
            }
        } catch (error: Exception) {
            report.put("error", error.javaClass.simpleName)
            throw AssertionError("Frost contract check failed; see redacted FROST_NATIVE summary")
        } finally { println("FROST_NATIVE $report") }
    }
}
