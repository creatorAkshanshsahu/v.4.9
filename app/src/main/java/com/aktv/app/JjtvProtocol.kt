package com.aktv.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** The static data contract advertised by /pind. No remote JavaScript, adverts or embeds are executed. */
internal object JjtvProtocol {
    data class Config(
        val channels: String,
        val primaryCookie: String?,
        val fallbackCookie: String?,
        val starCookies: String?,
        val mediaHeaders: Map<String, String>
    )

    private val constants = Regex("""\b(?:const|let|var)\s+(CHANNELS_URL|COOKIE_PRIMARY|COOKIE_FALLBACK|COOKIE_STAR_SPORTS)\s*=\s*(["'`])([^"'`]*)\2""")
    private val headerAssignments = Regex("""\b[A-Za-z_$][A-Za-z0-9_$.]*\.headers\s*\[\s*(["'])(Referer|User-Agent|Origin)\1\s*]\s*=\s*(["'`])([^"'`]*)\3""", RegexOption.IGNORE_CASE)
    private val token = Regex("""__hdnea__=([^&\s;"'<>]+)""")

    fun config(script: String, page: String): Config? {
        val values = constants.findAll(script).associate { it.groupValues[1] to literal(it.groupValues[3]) }
        val channels = endpoint(values["CHANNELS_URL"], page) ?: return null
        val headers = linkedMapOf<String, String>()
        headerAssignments.findAll(script).forEach { match ->
            val value = literal(match.groupValues[4])
            if (value != null && value.length <= 512 && value.none { it == '\r' || it == '\n' }) {
                val name = when (match.groupValues[2].lowercase()) {
                    "user-agent" -> "User-Agent"; "referer" -> "Referer"; else -> "Origin"
                }
                headers[name] = value
            }
        }
        return Config(channels, endpoint(values["COOKIE_PRIMARY"], page), endpoint(values["COOKIE_FALLBACK"], page),
            endpoint(values["COOKIE_STAR_SPORTS"], page), headers)
    }

    private fun literal(value: String): String? = value.takeUnless { it.contains("\${") }
        ?.replace("\\/", "/")?.replace("\\u002F", "/")

    private fun endpoint(value: String?, page: String): String? = try {
        if (value.isNullOrBlank()) null else URI(page).resolve(value).let { uri ->
            uri.toASCIIString().takeIf { uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.host != "localhost" && !uri.host.endsWith(".local") && uri.fragment == null }
        }
    } catch (_: Exception) { null }

    /** The deployed page also accepts a comma-separated sequence of channel objects. First exact ID wins. */
    fun catalog(text: String): Map<String, JSONObject> {
        val body = text.trim().removePrefix("\uFEFF")
        val array = JSONArray(if (body.startsWith('[')) body else "[$body]")
        require(array.length() <= 10_000) { "Channel catalogue too large" }
        val channels = LinkedHashMap<String, JSONObject>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.opt("id")?.takeUnless { it == JSONObject.NULL }?.toString() ?: continue
            if (id.matches(Regex("[0-9]{1,10}"))) channels.putIfAbsent(id, item)
        }
        return channels
    }

    fun urls(channel: JSONObject): List<String> = listOf("url", "url1", "url2", "url3")
        .mapNotNull { channel.opt(it) as? String }.filter { it.isNotBlank() }
        .map { it.replaceFirst("bpk-tv//", "bpk-tv/") }.distinct()

    fun isStarUrl(url: String): Boolean = try {
        URI(url).host.orEmpty().equals("jiotvpllive.cdn.jio.com", ignoreCase = true)
    } catch (_: Exception) { false }

    fun regularCookie(body: String?, fallback: Boolean = false): String {
        if (body.isNullOrBlank()) return ""
        return try {
            if (fallback) (JSONObject(body).opt("cookie") as? String).orEmpty()
            else JSONArray(body).let { array ->
                (0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it)?.opt("cookie") as? String }
                    .firstOrNull { it.isNotBlank() }.orEmpty()
            }
        } catch (_: Exception) { "" }
    }

    /** Failed probes can still contain a valid signed redirect. Never use another channel's token. */
    fun starCookie(body: String?, id: String): String {
        if (body.isNullOrBlank()) return ""
        return try {
            val root = JSONObject(body)
            for (name in listOf("successful_results", "failed_results")) {
                val entries = root.optJSONArray(name) ?: continue
                for (i in 0 until entries.length()) {
                    val item = entries.optJSONObject(i) ?: continue
                    if (item.opt("channel_id")?.toString() != id) continue
                    val finalUrl = (item.optJSONObject("error_details")?.opt("final_url") as? String).orEmpty()
                        .ifBlank { (item.opt("final_url") as? String).orEmpty() }
                    token.find(finalUrl)?.let { return it.value }
                }
            }
            ""
        } catch (_: Exception) { "" }
    }

    fun streams(channel: JSONObject, config: Config, regular: String, fallback: String, star: String,
                page: String, website: String): List<TgStream> {
        val candidates = JSONArray()
        val keys = channel.optJSONArray("keys")?.takeIf { it.length() > 0 }
        // Match the page: new multi-key data takes precedence over the old single-key fields.
        val drmFields = if (keys != null) JSONObject().put("keys", keys) else JSONObject()
            .put("keyId", channel.opt("keyId")).put("key", channel.opt("key"))
        val headers = JSONObject(mapOf("Origin" to website, "Referer" to "$website/") + config.mediaHeaders)
        val primary = regular.ifBlank { fallback }
        val secondary = if (regular.isBlank()) "" else fallback
        for (url in urls(channel)) {
            val cookies = if (isStarUrl(url)) listOf(star).filter { it.isNotBlank() }
            else listOf(primary, secondary).filter { it.isNotBlank() }.distinct().ifEmpty { listOf("") }
            for (cookie in cookies) {
                candidates.put(JSONObject(drmFields.toString()).put("url", url).put("cookie", cookie)
                    .put("headers", headers).put("label", "JJTV · Server ${candidates.length() + 1}"))
            }
        }
        val body = JSONObject().put("servers", candidates).toString().toByteArray(Charsets.UTF_8)
        return TgProtocol.streams(body, "application/json", page).map { it.copy(requestOrigin = website) }
    }
}
