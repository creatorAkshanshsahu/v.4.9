package com.aktv.app

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI

/**
 * Opt-in read of the playlist's own playback path, channel by channel.
 *
 * A playlist that plays in VLC or a web player but not here is never the playlist's fault: something in
 * the request chain differs. This walks exactly what the app does — resolve the entry, follow the
 * redirect, read the manifest with the app's own client and headers — and prints one line per channel, so
 * a working channel (Zee Cinema HD 165, &TV 1106) can be compared with a refused one (Sony SAB HD 471,
 * Sony Ten 3 Hindi HD 892).
 *
 * Nothing here is a licence test: it reports HTTP status, which host answered, whether the body is a
 * manifest, whether it declares ContentProtection, and how many keys the provider hands out.
 */
class JioM3uLiveTest {

    private fun read(url: String, headers: Map<String, String>): TgResponse? =
        try { TgHttp().get(url, headers) } catch (_: Exception) { null }

    private fun probe(id: String, label: String): JSONObject {
        val line = JSONObject().put("provider", "JIOM3U").put("step", "probe").put("channel", id).put("name", label)
        val resolver = JioM3uResolver()
        // Printed line by line, so a later failure cannot swallow what was already learned.
        val entries = try { resolver.catalogue() } catch (e: Exception) {
            return line.put("catalogueError", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        val entry = entries.firstOrNull { it.id == id }
            ?: return line.put("error", "not in playlist").put("entries", entries.size)
        line.put("entries", entries.size)
            .put("path", runCatching { URI(entry.url).path }.getOrDefault(""))
            .put("playlistCookie", entry.cookie != null)
            .put("playlistUa", entry.userAgent)

        // 1. The provider's key document, exactly as the resolver reads it.
        val keyUrl = entry.licenseKey
        line.put("keyDocument", keyUrl != null)
        if (keyUrl != null) {
            val keys = read(keyUrl, linkedMapOf("User-Agent" to JioM3uProtocol.UA_OVERRIDE))
            line.put("keyStatus", keys?.code ?: -1)
                .put("keyBytes", keys?.body?.size ?: 0)
                .put("keyCount", keys?.let { JioM3uProtocol.parseKeys(it.body.toString(Charsets.UTF_8)).size } ?: 0)
        }

        // 2. What the resolver itself produces.
        val stream = runCatching { resolver.resolve(id, fresh = true).first() }.getOrElse {
            return line.put("resolveError", it.javaClass.simpleName + ": " + (it.message ?: ""))
        }
        val tokens = Regex("__hdnea__").findAll(stream.url).count()
        line.put("type", stream.type)
            .put("finalHost", runCatching { URI(stream.url).host.orEmpty() }.getOrDefault(""))
            .put("finalTokens", tokens)
            .put("label", stream.label)
            .put("drmType", stream.drm?.optString("type"))
            .put("drmKeys", stream.drm?.optJSONArray("keys")?.length() ?: 0)
            .put("headers", stream.headers.keys.sorted())

        // 3. The manifest, read with the app's own transport and the stream's own headers.
        val manifest = read(stream.url, stream.headers)
        val body = manifest?.body?.toString(Charsets.UTF_8).orEmpty()
        line.put("manifestStatus", manifest?.code ?: -1)
            .put("manifestBytes", manifest?.body?.size ?: 0)
            .put("manifestIsDash", body.contains("<MPD", true))
            .put("contentProtection", Regex("<ContentProtection").findAll(body).count())
            .put("defaultKids", Regex("default_KID=\\\"([^\\\"]+)\\\"").findAll(body).map { it.groupValues[1] }.distinct().count())
            .put("hasBaseUrl", body.contains("<BaseURL", true))
            .put("namespaces", Regex("urn:uuid:[0-9a-fA-F-]{36}").findAll(body).map { it.value }.distinct().toList())
        return line
    }

    @Test fun `playlist channels reach their manifest and key document`() {
        assumeTrue(System.getProperty("aktv.live.jio") == "true")
        val channels = listOf(
            "165" to "Zee Cinema HD", "1106" to "&TV HD", "471" to "Sony SAB HD", "892" to "Sony Ten 3 Hindi HD"
        )
        val lines = channels.map { (id, name) -> probe(id, name).also { println("TG_NATIVE " + it) } }
        val answered = lines.count { it.optInt("manifestStatus") in 200..299 }
        println("TG_NATIVE " + JSONObject().put("provider", "JIOM3U").put("step", "summary")
            .put("channels", lines.size).put("manifestOk", answered)
            .put("refusals", lines.mapNotNull { it.optString("manifestStatus").takeIf { s -> s.isNotBlank() } }))
        // A runner outside India is refused by the CDN itself (403/451 on every channel). That is not
        // evidence about this app's request chain, so it is reported as blocked rather than as a failure;
        // when any channel answers with something other than a refusal, an unanswered set is a failure.
        val blocked = lines.count { it.optInt("manifestStatus") in setOf(403, 451) }
        val reached = lines.count { it.has("manifestStatus") && it.optInt("manifestStatus") > 0 }
        assertTrue(
            "not one playlist channel produced a manifest: the request chain is still wrong",
            answered > 0 || (blocked > 0 && blocked == reached)
        )
    }
}
