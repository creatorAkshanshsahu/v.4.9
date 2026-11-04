package com.aktv.app

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
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
        // Include working Zee Cinema HD (165), the reported Star Gold HD (156), and Sony Ten 3
        // Hindi HD/SD (Jio IDs 892/524) for targeted live metadata and request-profile diagnostics.
        val ids = listOf("165", "156", "524", "892", "872", "1106", "460", "1123", "461", "1114", "1142", "1108", "362", "1109",
            "1141", "1984", "1985", "1389", "3277", "1124", "3278", "1651", "1650", "1122", "3273", "2853", "3274", "2852")
        val missing = ArrayList<String>()
        var functionSaved = false
        TgResolver(tracked, saveFunction = { functionSaved = true }).use { resolver ->
            for (id in ids) {
                val report = JSONObject().put("provider", "TG").put("channel", id)
                try {
                    val sources = WebsiteRequestParity.playbackSources(LiveProvider.TG, resolver.resolve(id, fresh = false))
                    report.put("sources", JSONArray().apply {
                        sources.forEachIndexed { index, source ->
                            put(JSONObject().put("index", index + 1).put("label", source.label).put("type", source.type)
                                .put("host", URI(source.url).host).put("cookiePresent", source.cookie.isNotBlank())
                                .put("tokenSecondsLeft", tokenSecondsLeft(source))
                                .put("tokenTimingStatus", tokenTimingStatus(source))
                                .put("keyCount", source.drm?.let { ClearKey.pairs(it).size } ?: 0))
                        }
                    })
                    if (id in setOf("165", "156", "524", "892", "872", "1106")) {
                        report.put("manifests", JSONArray().apply { sources.take(4).forEach { put(probe(it)) } })
                    }
                } catch (error: Exception) {
                    missing.add(id)
                    report.put("metadataError", error.javaClass.simpleName)
                        .put("metadataHttpStatus", when (error) {
                            is TgResolveException -> error.status
                            is JjtvHttpException -> error.status
                            else -> null
                        })
                }
                println("TG_NATIVE $report")
            }
        }
        assertTrue("The native resolver did not discover a working TG server function", functionSaved)
        assertTrue("TG metadata unavailable for channel IDs: ${missing.joinToString()}", missing.isEmpty())
        assertFalse("Native resolution fell back to the incomplete public API instead of TG's player configuration",
            requested.any { it.startsWith("/api/public/channels/") })
    }

    @Test fun `JJTV native configuration supplies exact channel keys and per-channel cookies`() {
        assumeTrue(System.getProperty("aktv.live.tg") == "true")
        val ids = listOf("165", "156", "524", "892", "816", "872", "1106", "460", "1123", "461", "1114", "1142", "1108", "362", "1109",
            "1141", "1984", "1985", "1389", "3277", "1124", "3278", "1651", "1650", "1122", "3273", "2853", "3274", "2852")
        val resolver = JjtvResolver()
        val missing = ArrayList<String>()
        // A live catalogue is maintained upstream. An ID it does not list can never be streamed by
        // this provider, and TG still covers it, so classify it separately from a resolver failure.
        val absent = ArrayList<String>()
        val missingKeys = ArrayList<String>()
        for (id in ids) {
            val report = JSONObject().put("provider", "JJTV").put("channel", id)
            try {
                val sources = resolver.resolve(id)
                report.put("sources", JSONArray().apply {
                    sources.forEach { source ->
                        put(JSONObject().put("host", URI(source.url).host).put("type", source.type)
                            .put("keyCount", source.drm?.let { ClearKey.pairs(it).size } ?: 0)
                            .put("cookiePresent", source.cookie.isNotBlank())
                            .put("tokenSecondsLeft", tokenSecondsLeft(source))
                            .put("tokenTimingStatus", tokenTimingStatus(source)))
                    }
                })
                if (id in setOf("165", "156", "524", "892", "816", "872", "1106")) {
                    if (id in setOf("816", "872", "1106") &&
                        sources.none { it.drm?.let { drm -> ClearKey.pairs(drm).isNotEmpty() } == true }) missingKeys.add(id)
                    report.put("manifests", JSONArray().apply { sources.take(2).forEach { put(probe(it)) } })
                }
            } catch (error: Exception) {
                if (error is IOException && error.message?.contains("no matching channel ID") == true) absent.add(id)
                else missing.add(id)
                report.put("metadataError", error.javaClass.simpleName)
                    .put("metadataOutcome", if (id in absent) "not-in-catalogue" else "resolve-failed")
                    .put("metadataHttpStatus", when (error) {
                        is TgResolveException -> error.status
                        is JjtvHttpException -> error.status
                        else -> null
                    })
            }
            println("TG_NATIVE $report")
        }
        assertTrue("Replacement live site metadata unavailable for channel IDs: ${missing.joinToString()}", missing.isEmpty())
        if (absent.isNotEmpty()) println("TG_NATIVE " + JSONObject().put("provider", "JJTV")
            .put("step", "notInCatalogue").put("channels", JSONArray(absent))
            .put("note", "Upstream catalogue omits these IDs; TG advertises them instead."))
        assertTrue("JJTV omitted expected key data for channel IDs: ${missingKeys.joinToString()}", missingKeys.isEmpty())
    }

    /**
     * Opt-in read of the real playlist that powers the one Sony section. The section is bound to the
     * `allinonereborn2.online` m3u8 catalogue, so this proves against the live feed that the page's 22
     * tiles still bind to the channels that playlist actually carries, and reports any it drops.
     */
    @Test fun `sony section binds the page tiles to the live playlist`() {
        assumeTrue(System.getProperty("aktv.live.jio") == "true")
        val response = TgHttp().get(
            JioM3uProtocol.PLAYLIST,
            linkedMapOf("Accept" to "*/*", "User-Agent" to JioM3uProtocol.UA_OVERRIDE)
        )
        assertEquals("the playlist did not answer 200", 200, response.code)
        val entries = JioM3uProtocol.parse(response.body.toString(Charsets.UTF_8))
        assertTrue("the playlist parsed to no channels", entries.size > 20)
        val catalogue = entries.map { MainActivity.Channel(it.id, it.name, it.category, it.logo, jio = true) }
        val section = SonyPage.channels(catalogue)
        println("TG_NATIVE " + JSONObject().put("provider", "SonyPage").put("step", "sonySection")
            .put("playlistChannels", entries.size).put("tiles", section.size)
            .put("names", JSONObject(section.associate { it.id to it.name } as Map<*, *>)))
        // Every tile is playable from this playlist only, and the page's channels are overwhelmingly present.
        assertTrue(section.all { it.pinned && it.jio && !it.v2 })
        assertTrue("only ${section.size} of ${SonyPage.TILES.size} page tiles bound to the live playlist",
            section.size >= SonyPage.TILES.size - 2)
    }

    /**
     * Opt-in read of the replacement site's current player page. Names only, so a site that moves its
     * endpoints is reported instead of silently failing; the resolver reads them at runtime anyway.
     */
    @Test fun `replacement live site still advertises the native player constants`() {
        assumeTrue(System.getProperty("aktv.live.tg") == "true")
        val page = "${JjtvResolver.SITE}/pind?id=872"
        val http = TgHttp()
        val html = http.get(page, linkedMapOf("Accept" to "text/html"))
        println("TG_NATIVE " + JSONObject().put("provider", "GioTV").put("step", "playerPage")
            .put("status", html.code).put("bytes", html.body.size).put("site", JjtvResolver.SITE))
        assertEquals("The replacement player page did not answer with HTTP 200", 200, html.code)
        val config = JjtvProtocol.config(html.body.toString(Charsets.UTF_8), page)
        println("TG_NATIVE " + JSONObject().put("provider", "GioTV").put("step", "constants")
            .put("channels", config != null).put("primaryCookie", config?.primaryCookie != null)
            .put("starCookie", config?.starCookies != null))
        assertNotNull("The replacement player page no longer advertises its channel document", config)
        assertNotNull("The replacement player page no longer advertises a cookie document", config?.primaryCookie)
    }

    /** Public timing only; positive expiry is not proof that the provider accepts the signature. */
    private fun tokenSecondsLeft(source: TgStream): Long? =
        StreamRequestPolicy.expiry(source.url, source.cookie)?.let { (it - System.currentTimeMillis()) / 1000 }

    private fun tokenTimingStatus(source: TgStream): String = when (val seconds = tokenSecondsLeft(source)) {
        null -> "opaque-or-unsigned"
        in Long.MIN_VALUE..0L -> "expired-by-timestamp"
        in 1L..60L -> "expires-within-60-seconds"
        else -> "timestamp-not-expired"
    }

    /** Inspect the Media3 transport, not a second guessed HTTP profile. A manifest is NOT playback proof. */
    private fun probe(source: TgStream): JSONObject {
        val report = JSONObject().put("host", URI(source.url).host)
            .put("requestStyle", if (source.omitReferrer) "browser-no-referrer" else "declared-native")
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
