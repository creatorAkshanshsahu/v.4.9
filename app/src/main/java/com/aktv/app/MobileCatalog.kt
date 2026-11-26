package com.aktv.app

import org.json.JSONObject

/**
 * The TG catalogue wire shapes (`/api/public/channels`, `/api/public/v2/channels`), parsed the
 * same way for the TV shelf and the mobile edition. Android-free so it is unit-tested off-device.
 */
internal object MobileCatalog {
    data class Entry(val id: String, val name: String, val category: String, val logo: String)

    fun parseV1(json: String): List<Entry> {
        val arr = try { JSONObject(json).getJSONArray("channels") } catch (_: Exception) { return emptyList() }
        val raw = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            val name = o.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) null
            else Entry(id, name, o.optString("category").ifBlank { "Other" }, o.optString("logo"))
        }
        // Same rule as the TV shelf: ephemeral event streams stay out unless they are all there is.
        return raw.filterNot { PortalFallback.isEventStream(it.id) }.ifEmpty { raw }
    }

    fun parseV2(json: String): List<Entry> {
        val arr = try { JSONObject(json).getJSONArray("channels") } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            val name = o.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) null
            else {
                val first = name.first().uppercaseChar()
                val fallback = if (first.isLetter()) first.toString() else "#"
                Entry(id, name, o.optString("category").ifBlank { fallback }, o.optString("logo"))
            }
        }.sortedBy { it.name.lowercase() }
    }
}
