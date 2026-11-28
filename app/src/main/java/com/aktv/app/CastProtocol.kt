package com.aktv.app

import org.json.JSONObject

/**
 * The "Play on TV" wire: one JSON line from the mobile app to a TV on the same network over a
 * plain socket, advertised with [SERVICE_TYPE]. No auth (LAN only), no secrets on the wire — the
 * TV resolves everything itself from its own accounts. Pure, so the shape is unit-tested.
 */
internal object CastProtocol {
    const val SERVICE_TYPE = "_streamr._tcp."
    const val SERVICE_PREFIX = "STREAMR-TV"
    const val PORT = 52431
    const val KIND_LIVE = "live"
    const val KIND_VOD = "vod"

    data class Command(
        val kind: String,
        val provider: String,
        val id: String,
        val label: String,
        val season: Int = 0,
        val episode: Int = 0,
        val tmdb: Int = 0,
        val type: String = "",
        val url: String = ""
    )

    fun encodeLive(provider: String, id: String, label: String, url: String = ""): String =
        JSONObject().put("v", 1).put("kind", KIND_LIVE).put("provider", provider)
            .put("id", id).put("label", label.take(120)).put("url", url.take(2048)).toString()

    fun encodeVod(provider: String, tmdb: Int, type: String, season: Int, episode: Int, label: String): String =
        JSONObject().put("v", 1).put("kind", KIND_VOD).put("provider", provider).put("tmdb", tmdb)
            .put("type", type).put("season", season).put("episode", episode)
            .put("label", label.take(120)).toString()

    fun decode(line: String): Command? {
        if (line.isBlank() || line.length > 8_192) return null
        val o = try { JSONObject(line) } catch (_: Exception) { return null }
        if (o.optInt("v", 0) != 1) return null
        return when (o.optString("kind")) {
            KIND_LIVE -> {
                val provider = o.optString("provider").trim().take(24)
                val id = o.optString("id").trim().take(128)
                if (provider.isEmpty() || id.isEmpty()) return null
                Command(KIND_LIVE, provider, id, o.optString("label").trim().take(120),
                    url = o.optString("url").trim().take(2048))
            }
            KIND_VOD -> {
                val tmdb = o.optInt("tmdb", 0)
                val type = o.optString("type").trim().take(12)
                if (tmdb <= 0 || (type != "movie" && type != "tv")) return null
                Command(KIND_VOD, o.optString("provider").trim().take(24), "", o.optString("label").trim().take(120),
                    o.optInt("season", 0), o.optInt("episode", 0), tmdb, type)
            }
            else -> null
        }
    }
}
