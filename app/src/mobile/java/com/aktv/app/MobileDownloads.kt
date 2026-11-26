package com.aktv.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/**
 * Offline files: plain MP4 downloads through the system [DownloadManager], listed from its own
 * registry so the Saved tab survives reboots. Stream-only sources (HLS/DASH/live) cannot be saved
 * this way — the download button says so instead of failing silently.
 */
internal object MobileDownloads {
    data class Entry(val id: Long, val label: String, val file: String, val size: Long)

    private fun prefs(context: Context) =
        context.getSharedPreferences("aktv_mobile_downloads", Context.MODE_PRIVATE)

    fun enqueue(context: Context, url: String, headers: Map<String, String>, filename: String): Long {
        val safe = filename.replace(Regex("[^A-Za-z0-9._() -]"), "_").take(120).ifBlank { "video.mp4" }
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(safe.removeSuffix(".mp4"))
            setDescription("STREAMR Mobile")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, "STREAMR/$safe")
            headers.forEach { (name, value) ->
                if (!name.equals("Cookie", true) && value.isNotBlank() && value.length < 1024) {
                    runCatching { addRequestHeader(name, value) }
                }
            }
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return -1
        val id = runCatching { manager.enqueue(request) }.getOrElse { return -1 }
        prefs(context).edit().putString("dl_$id", safe.removeSuffix(".mp4")).apply()
        return id
    }

    fun list(context: Context): List<Entry> {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return emptyList()
        val known = prefs(context).all.mapNotNull { (key, value) ->
            if (!key.startsWith("dl_")) null
            else key.removePrefix("dl_").toLongOrNull()?.let { it to value.toString() }
        }.toMap()
        if (known.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        val cursor = runCatching {
            manager.query(DownloadManager.Query().setFilterById(*known.keys.toLongArray()))
        }.getOrNull() ?: return emptyList()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusCol = it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val uriCol = it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val sizeCol = it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (it.moveToNext()) {
                if (it.getInt(statusCol) != DownloadManager.STATUS_SUCCESSFUL) continue
                val id = it.getLong(idCol)
                val uri = runCatching { it.getString(uriCol) }.getOrNull().orEmpty()
                val file = runCatching { Uri.parse(uri).path }.getOrNull().orEmpty()
                if (file.isBlank() || !java.io.File(file).exists()) continue
                out += Entry(id, known[id] ?: "Saved video", file, it.getLong(sizeCol).coerceAtLeast(0L))
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun remove(context: Context, id: Long) {
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.remove(id)
        prefs(context).edit().remove("dl_$id").apply()
    }
}
