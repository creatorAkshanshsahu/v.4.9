package com.aktv.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app updates from the project's own GitHub release.
 *
 * Every CI build publishes a rolling `latest` release containing `update.json` and both APK variants,
 * so the app can install a newer build over itself instead of the user fetching and reinstalling an
 * APK by hand. Nothing here trusts a version name: the published `versionCode` and the SHA-256 of the
 * downloaded file are checked before Android's package installer is invoked.
 */
internal object UpdateConfig {
    const val OWNER = "creatorAkshanshsahu"
    const val REPO = "v.4.9"
    // Plain concatenation keeps these compile-time constants (no template in a const initializer).
    const val ASSET_BASE = "https://github.com/" + OWNER + "/" + REPO + "/releases/latest/download"
    const val MANIFEST_URL = ASSET_BASE + "/update.json"
    /**
     * The release API is the primary source. `releases/latest/download/...` is a redirect that GitHub's own
     * CDN caches *without* honouring a cache-busting query (it strips it on the 302), so right after a
     * publish the asset path can keep answering with the previous release's 404 for a long time. The API
     * answers from the database and carries each asset's `digest`, which is the checksum the installer needs.
     */
    const val RELEASE_API = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/tags/latest"
    /**
     * Automatic checks are throttled; an explicit check always runs. Six hours was too long: a build pushed
     * an hour after the last check reached nobody until the next half-day, which is exactly what happened
     * between 4.9.62 and 4.9.63. BUILD_GRACE_MS is the floor a network callback must observe.
     */
    const val CHECK_INTERVAL_MS = 90 * 1000L
    const val BUILD_GRACE_MS = 45_000L
    const val MAX_APK_BYTES = 300L * 1024 * 1024
}

internal data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long
) {
    val label: String get() = if (versionName.isBlank()) "code $versionCode" else "$versionName (code $versionCode)"
}

/** Pure parsing/verification rules, kept Android-free so they can be tested off-device. */
internal object UpdateManifest {

    /** This APK is built per ABI (`arm32`/`arm64`); pick the matching published asset. */
    fun abiTag(abis: List<String> = Build.SUPPORTED_ABIS.toList()): String =
        if (abis.any { it.contains("64") }) "arm64" else "arm32"

    fun parse(body: String, abi: String = abiTag()): UpdateRelease? {
        if (body.isBlank() || body.length > 64 * 1024) return null
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        val code = root.optLong("versionCode", 0L).takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return null
        val apks = root.optJSONObject("apks") ?: return null
        val entry = apks.optJSONObject(abi) ?: apks.optJSONObject("universal") ?: return null
        val url = entry.optString("url", "").trim()
            .takeIf { it.startsWith("https://", true) && it.length <= 2_048 && it.none { c -> c.isISOControl() } }
            ?: return null
        val sha = entry.optString("sha256", "").trim().lowercase()
            .takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        return UpdateRelease(
            versionCode = code,
            versionName = root.optString("versionName", "").trim().take(40),
            notes = root.optString("notes", "").trim().take(1_200),
            apkUrl = url,
            sha256 = sha,
            size = entry.optLong("size", 0L).coerceIn(0L, UpdateConfig.MAX_APK_BYTES)
        )
    }

    /**
     * A build is newer when its published code is higher, or when the same code was re-published with a
     * different file (a re-run). The second case needs the checksum of the build this app last installed,
     * so an unchanged installation is never prompted again.
     */
    fun isNewer(installedCode: Long, release: UpdateRelease, installedSha: String?): Boolean = when {
        release.versionCode > installedCode -> true
        release.versionCode < installedCode -> false
        else -> installedSha != null && !installedSha.equals(release.sha256, true)
    }

    /**
     * Silent checks never interrupt playback: a release found while video is playing is held for the
     * shelf instead of shown as a modal. A check the viewer asked for always answers at once.
     */
    fun defersToShelf(interactive: Boolean, playbackActive: Boolean): Boolean = !interactive && playbackActive

    /**
     * Reads the same [UpdateRelease] from GitHub's release JSON. The version is published in the release
     * notes ("Version code **119**", written by `.github/scripts/publish-update.py`) and each asset carries
     * a `sha256:` digest, so nothing has to be trusted that the installer would not check anyway.
     */
    fun fromReleaseApi(body: String, abi: String): UpdateRelease? {
        if (body.isBlank() || body.length > 512 * 1024) return null
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        val notes = root.optString("body", "")
        val code = Regex("Version code \\*\\*(\\d{1,9})\\*\\*").find(notes)?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return null
        val name = Regex("#\\s*STREAMR\\s+([0-9A-Za-z._+-]{1,40})").find(notes)?.groupValues?.get(1).orEmpty()
        val wanted = if (abi == "arm64") "STREAMR-arm64.apk" else "STREAMR-arm32.apk"
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != wanted) continue
            if (asset.optString("state", "uploaded") != "uploaded") return null
            val url = asset.optString("browser_download_url", "").trim()
                .takeIf { it.startsWith("https://", true) && it.length <= 2_048 && it.none { c -> c.isISOControl() } }
                ?: return null
            val sha = asset.optString("digest", "").trim().removePrefix("sha256:").lowercase()
                .takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
            return UpdateRelease(
                versionCode = code,
                versionName = name.trim().take(40),
                notes = notes.trim().take(1_200),
                apkUrl = url,
                sha256 = sha,
                size = asset.optLong("size", 0L).coerceIn(0L, UpdateConfig.MAX_APK_BYTES)
            )
        }
        return null
    }

    /** Integer percent for the progress bar. Unknown totals stay at 0 instead of dividing by zero. */
    fun progressPercent(written: Long, total: Long): Int =
        if (total <= 0) 0 else ((written * 100) / total).toInt().coerceIn(0, 100)

    /**
     * Start byte of a `Content-Range` answer (`bytes 100-199/1000` -> 100). Null when the server sent
     * no usable range, which is when a resume must be abandoned instead of appended to blindly.
     */
    fun rangeStart(contentRange: String?): Long? {
        val match = Regex("^bytes\\s+(\\d+)-\\d+/").find(contentRange?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it >= 0 }
    }

    /** Total file size of a `Content-Range` answer (`bytes 100-199/1000` -> 1000). */
    fun rangeTotal(contentRange: String?): Long? {
        val match = Regex("^bytes\\s+(?:\\d+-\\d+|\\*)/([0-9]+)").find(contentRange?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it in 1..UpdateConfig.MAX_APK_BYTES }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Network/installation side of the updater. One download at a time; no credential is involved. */
internal object Updater {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads get their own client: a 90MB APK on a slow link legitimately takes minutes, so there
     * is no whole-call deadline — only the per-read stall guard, which fails fast when bytes stop
     * flowing and lets the retry loop below resume instead of restarting.
     */
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** A finished file that fails its checksum: the retry loop drops it and starts over. */
    private class ChecksumMismatch : IOException("Downloaded update did not match its published checksum")

    fun installedCode(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (_: Exception) {
        0L
    }

    /** Returns a release only when it is actually newer than this installation. */
    fun check(context: Context): UpdateRelease? {
        val abi = UpdateManifest.abiTag()
        // The release API first: it is answered from GitHub's database, whereas the download path is a
        // redirect whose CDN copy is not invalidated by a query string (GitHub strips it on the 302) and
        // can therefore keep answering with the previous release's 404 for hours after a publish. The
        // manifest asset remains the fallback for networks that cannot reach api.github.com.
        val release = fetchFromApi(abi) ?: fetchManifest(abi)
            ?: throw IOException("No update source answered")
        val prefs = context.getSharedPreferences("aktv", Context.MODE_PRIVATE)
        // The running binary stays the old one until the system swap finishes, so a build this app already
        // handed to the installer counts as installed - otherwise the same update is offered on every launch.
        val handedOver = prefs.getLong("update_installed_code", 0L)
        val known = maxOf(installedCode(context), handedOver)
        return release.takeIf {
            UpdateManifest.isNewer(known, it, prefs.getString("update_installed_sha", null))
        }
    }

    private fun fetchFromApi(abi: String): UpdateRelease? = try {
        val url = UpdateConfig.RELEASE_API + "?t=" + (System.currentTimeMillis() / 60_000L)
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Cache-Control", "no-store")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Release API HTTP ${response.code}")
            response.body?.string()?.let { UpdateManifest.fromReleaseApi(it, abi) }
        }
    } catch (_: Exception) {
        null
    }

    private fun fetchManifest(abi: String): UpdateRelease? {
        // Kept for networks where the API host is blocked; the query string is still sent because a proxy
        // in front of the redirect may honour it even though GitHub's own CDN does not.
        val url = UpdateConfig.MANIFEST_URL + "?t=" + (System.currentTimeMillis() / 60_000L)
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update service HTTP ${response.code}")
            response.body?.string()
        } ?: throw IOException("Update service returned no body")
        return UpdateManifest.parse(body, abi) ?: throw IOException("Update service returned an unusable manifest")
    }

    fun download(context: Context, release: UpdateRelease, onProgress: (Int) -> Unit, cancelled: () -> Boolean): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val name = "STREAMR-${release.versionCode}.apk"
        // The `.part` file is resume state: bytes from an earlier attempt — possibly from a previous app
        // launch — are kept and continued, never re-downloaded. On a link that cannot finish 90MB in one
        // go, restarting from zero is what makes an update feel impossibly slow.
        dir.listFiles()?.forEach { old -> if (old.name != name && old.name != "$name.part") old.delete() }
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        if (release.size > 0 && part.exists() && part.length() > release.size) part.delete()
        if (release.size > 0 && part.exists() && part.length() == release.size &&
            UpdateManifest.sha256(part).equals(release.sha256, true)
        ) {
            // The bytes already finished arriving (the app died before installing, say): no network at all.
            finishPart(part, target)
            onProgress(100)
            return target
        }
        var attempt = 0
        var lastError: IOException? = null
        while (attempt < 3) {
            if (cancelled()) throw IOException("Update download cancelled")
            try {
                if (!downloadAttempt(release, part, onProgress, cancelled).equals(release.sha256, true)) {
                    // A poisoned prefix must not poison the retry: drop everything and start over.
                    part.delete()
                    throw ChecksumMismatch()
                }
                finishPart(part, target)
                onProgress(100)
                return target
            } catch (error: IOException) {
                lastError = error
                if (cancelled()) throw IOException("Update download cancelled")
                attempt++
                if (attempt < 3) {
                    try { Thread.sleep(1_000L * attempt) } catch (_: InterruptedException) {
                        throw IOException("Update download cancelled")
                    }
                }
            }
        }
        throw lastError ?: IOException("Update download failed")
    }

    /**
     * One download pass, resuming [part] when the server honours ranges. Returns the hex SHA-256 of the
     * complete file: the checksum is fed chunk by chunk as bytes arrive, so a 90MB file is never read
     * back from disk a second time just to verify it.
     */
    private fun downloadAttempt(
        release: UpdateRelease,
        part: File,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean
    ): String {
        var have = if (part.exists()) part.length() else 0L
        val digest = MessageDigest.getInstance("SHA-256")
        if (have > 0) {
            // Seed the running checksum from the resumed prefix: one sequential disk read, no network.
            part.inputStream().use { input ->
                val seed = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(seed)
                    if (count < 0) break
                    digest.update(seed, 0, count)
                }
            }
        }
        fun rangedRequest(from: Long): Request {
            val builder = Request.Builder().url(release.apkUrl)
            if (from > 0) builder.header("Range", "bytes=$from-")
            return builder.build()
        }
        var response = downloadClient.newCall(rangedRequest(have)).execute()
        if (response.code == 416 && have > 0) {
            // The server has fewer bytes than our prefix (the release was re-published smaller): the
            // prefix belongs to nothing, so drop it and fetch from zero instead of failing the update.
            response.close()
            part.delete(); have = 0; digest.reset()
            response = downloadClient.newCall(rangedRequest(0)).execute()
        }
        response.use {
            if (it.code != 200 && it.code != 206) throw IOException("Update download failed (HTTP ${it.code})")
            if (it.code == 200 && have > 0) {
                // The range was ignored: the prefix on disk belongs to nothing, start over.
                part.delete(); have = 0; digest.reset()
            }
            if (it.code == 206) {
                val start = UpdateManifest.rangeStart(it.header("Content-Range"))
                if (start != have) throw IOException("Update resume point was rejected")
                val rangedTotal = UpdateManifest.rangeTotal(it.header("Content-Range"))
                if (release.size > 0 && rangedTotal != null && rangedTotal != release.size) {
                    // The release changed mid-download; the retry loop fetches the new file from zero.
                    part.delete()
                    throw IOException("Update changed while downloading")
                }
            }
            val body = it.body ?: throw IOException("Update download was empty")
            val total = (if (it.code == 206) UpdateManifest.rangeTotal(it.header("Content-Range")) else null)
                ?: body.contentLength().takeIf { length -> length > 0 }
                ?: release.size
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, /* append = */ have > 0).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var written = have
                    var lastPercent = UpdateManifest.progressPercent(written, total)
                    // The bar starts where the resume left off, then moves at most once per percent: a full
                    // download used to post ~1,400 UI updates, one per 64KB chunk.
                    onProgress(lastPercent)
                    while (true) {
                        if (cancelled()) throw IOException("Update download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                        if (written > UpdateConfig.MAX_APK_BYTES) throw IOException("Update download was too large")
                        val percent = UpdateManifest.progressPercent(written, total)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    output.flush()
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun finishPart(part: File, target: File) {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            // renameTo can fail across volumes; copy is the fallback, not a failure.
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    /** Android only installs an APK whose package and version match what was advertised. */
    fun verifyPackage(context: Context, file: File, release: UpdateRelease): String? {
        val info = try { context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) } catch (_: Exception) { null }
            ?: return "The downloaded file is not an Android app."
        if (info.packageName != context.packageName) return "The downloaded file belongs to a different app."
        if (info.longVersionCode != release.versionCode) return "The downloaded build does not match the published version."
        return null
    }

    /** True when this build was signed by a different key, which Android refuses to install over. */
    @Suppress("DEPRECATION")
    fun signatureMismatch(context: Context, file: File): Boolean {
        val archive = try {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        } catch (_: Exception) { null } ?: return false
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        } catch (_: Exception) { return false }
        val incoming = archive.signatures?.map { it.toCharsString() }?.toSet() ?: return false
        val current = installed.signatures?.map { it.toCharsString() }?.toSet() ?: return false
        return incoming != current
    }

    fun canInstall(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true
    } catch (_: Exception) {
        true
    }

    fun openInstallSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    /** Hands the verified APK to the system installer through this app's FileProvider. */
    fun install(context: Context, file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        // A box without a package installer activity, or a revoked grant, must not crash the app.
        false
    }
}
