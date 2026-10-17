package com.aktv.app

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Opt-in diagnostic against the live site. Offline unit tests never require TG to be available. */
class TgLiveContractTest {
    @Test fun `native resolver discovers current servers and inspects actual manifests`() {
        assumeTrue(System.getProperty("aktv.live.tg") == "true")
        val requested = CopyOnWriteArrayList<String>()
        val transport = TgHttp()
        val tracked = TgTransport { url, headers ->
            requested.add(URI(url).path) // Public endpoint paths only; never the signed query.
            transport.get(url, headers)
        }
        val ids = listOf("872", "1106", "460", "1123", "461", "1114", "1142", "1108", "362", "1109",
            "1141", "1984", "1985", "1389", "3277", "1124", "3278", "1651", "1650", "1122", "3273", "2853", "3274", "2852")
        val missing = ArrayList<String>()
        var functionSaved = false
        TgResolver(tracked, saveFunction = { functionSaved = true }).use { resolver ->
            for (id in ids) {
                val report = JSONObject().put("channel", id)
                try {
                    val sources = resolver.resolve(id, fresh = false)
                    report.put("sources", JSONArray().apply {
                        sources.forEachIndexed { index, source ->
                            put(JSONObject().put("index", index + 1).put("label", source.label).put("type", source.type)
                                .put("host", URI(source.url).host).put("cookiePresent", source.cookie.isNotBlank())
                                .put("tokenSecondsLeft", StreamRequestPolicy.expiry(source.url, source.cookie)?.let { (it - System.currentTimeMillis()) / 1000 })
                                .put("keyCount", source.drm?.let { ClearKey.pairs(it).size } ?: 0))
                        }
                    })
                    if (id in setOf("872", "1106")) {
                        report.put("manifests", JSONArray().apply { sources.take(4).forEach { put(probe(it)) } })
                    }
                } catch (error: Exception) {
                    missing.add(id)
                    report.put("metadataError", error.javaClass.simpleName)
                }
                println("TG_NATIVE $report")
            }
        }
        assertTrue("The native resolver did not discover a working TG server function", functionSaved)
        assertTrue("TG metadata unavailable for channel IDs: ${missing.joinToString()}", missing.isEmpty())
        assertFalse("Native resolution fell back to the incomplete public API instead of TG's player configuration",
            requested.any { it.startsWith("/api/public/channels/") })
    }

    /** Inspect the Media3 transport, not a second guessed HTTP profile. A manifest is NOT playback proof. */
    private fun probe(source: TgStream): JSONObject {
        val report = JSONObject().put("host", URI(source.url).host)
        try {
            val client = StreamHttp.client(source).newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(source.url).build()).execute().use { response ->
                report.put("status", response.code).put("finalHost", response.request.url.host)
                if (!response.isSuccessful) return report
                val bytes = response.body?.byteStream()?.readNBytes(512 * 1024) ?: ByteArray(0)
                report.put("bytes", bytes.size)
                val text = bytes.toString(Charsets.UTF_8).trimStart()
                if (text.startsWith("#EXTM3U")) {
                    report.put("format", "hls").put("encrypted", text.contains("#EXT-X-KEY:"))
                } else if (text.startsWith('<') && text.contains("<MPD")) {
                    val factory = DocumentBuilderFactory.newInstance().apply {
                        isNamespaceAware = true
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        setFeature("http://xml.org/sax/features/external-general-entities", false)
                        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                        isXIncludeAware = false
                        isExpandEntityReferences = false
                    }
                    val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
                    val protections = document.getElementsByTagNameNS("*", "ContentProtection")
                    val schemes = linkedSetOf<String>()
                    for (i in 0 until protections.length) {
                        val scheme = protections.item(i).attributes.getNamedItem("schemeIdUri")?.nodeValue.orEmpty()
                        schemes.add(if (scheme.startsWith("urn:")) scheme.take(120) else "other")
                    }
                    val bases = document.getElementsByTagNameNS("*", "BaseURL")
                    val hosts = linkedSetOf<String>()
                    for (i in 0 until bases.length) {
                        URI(response.request.url.toString()).resolve(bases.item(i).textContent.trim()).host?.let { hosts.add(it) }
                    }
                    report.put("format", "dash").put("encrypted", protections.length > 0)
                        .put("drmSchemes", JSONArray(schemes)).put("mediaHosts", JSONArray(hosts))
                        .put("providedKeyCount", source.drm?.let { ClearKey.pairs(it).size } ?: 0)
                } else {
                    report.put("format", "not-a-manifest")
                }
            }
        } catch (error: Exception) {
            report.put("error", error.javaClass.simpleName) // Exception messages may contain signed URLs.
        }
        return report
    }
}
