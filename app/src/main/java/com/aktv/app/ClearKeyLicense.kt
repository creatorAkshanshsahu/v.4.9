package com.aktv.app

import androidx.media3.common.C
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A playlist-style ClearKey key document served over GET.
 *
 * The JioTV playlist this app reads advertises
 * `#KODIPROP:inputstream.adaptive.license_key=https://…/jtv-fetch/keyid.php?id=<channel>`, which is a
 * **key document** (`{"keys":[{"kty":"oct","k":"…","kid":"…"}]}`), not a DRM licence endpoint: it
 * answers a GET and needs no request body or challenge. Media3's own Widevine/Http callbacks POST a
 * challenge, so they cannot be used here.
 *
 * Nothing is cached or persisted: every DRM session asks the provider again, exactly like the playlist.
 */
class ClearKeyLicenseCallback(
    private val url: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
) : MediaDrmCallback {

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray =
        throw IOException("ClearKey does not use provisioning")

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        if (uuid != C.CLEARKEY_UUID) throw IOException("Not a ClearKey key request")
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        // The platform type can be null when Media3 creates a request without headers.
        request.headers?.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("ClearKey key response HTTP ${response.code}")
            val body = response.body ?: throw IOException("ClearKey key response was empty")
            if (body.contentLength() > MAX_BYTES) throw IOException("ClearKey key response was too large")
            val bytes = body.bytes()
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw IOException("ClearKey key response was unusable")
            if (JioM3uProtocol.parseKeys(String(bytes, Charsets.UTF_8)).isEmpty())
                throw IOException("ClearKey key response contained no usable key")
            return bytes
        }
    }

    companion object { private const val MAX_BYTES = JioM3uProtocol.MAX_KEY_BYTES }
}
