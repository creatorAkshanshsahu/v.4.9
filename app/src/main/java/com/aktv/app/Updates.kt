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
    const val MANIFEST_URL = "https://github.com/$OWNER/$REPO/releases/latest/download/update.json"
    const val ASSET_BASE = "https://github.com/$OWNER/$REPO/releases/latest/download"
    /** Automatic checks are throttled; an explicit check always runs. */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
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

    fun installedCode(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (_: Exception) {
        0L
    }

    /** Returns a release only when it is actually newer than this installation. */
    fun check(context: Context): UpdateRelease? {
        val request = Request.Builder().url(UpdateConfig.MANIFEST_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update service HTTP ${response.code}")
            response.body?.string()
        } ?: throw IOException("Update service returned no body")
        val release = UpdateManifest.parse(body) ?: throw IOException("Update service returned an unusable manifest")
        val prefs = context.getSharedPreferences("aktv", Context.MODE_PRIVATE)
        return release.takeIf {
            UpdateManifest.isNewer(installedCode(context), it, prefs.getString("update_installed_sha", null))
        }
    }

    fun download(context: Context, release: UpdateRelease, onProgress: (Int) -> Unit, cancelled: () -> Boolean): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { old -> if (old.name != "AK-TV-${release.versionCode}.apk") old.delete() }
        val target = File(dir, "AK-TV-${release.versionCode}.apk")
        val request = Request.Builder().url(release.apkUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Update download was empty")
            val total = if (body.contentLength() > 0) body.contentLength() else release.size
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        if (cancelled()) throw IOException("Update download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        if (written > UpdateConfig.MAX_APK_BYTES) throw IOException("Update download was too large")
                        if (total > 0) onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        if (!UpdateManifest.sha256(target).equals(release.sha256, true)) {
            target.delete()
            throw IOException("Downloaded update did not match its published checksum")
        }
        return target
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
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
