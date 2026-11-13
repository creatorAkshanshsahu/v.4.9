package com.aktv.app

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * What a portal needs in order to recognise a device: the portal address and the MAC / serial / device id
 * the subscription was issued to. The defaults are the set the viewer supplied; they are stored in the
 * app's own preferences so they can be changed without a new APK.
 */
internal data class StalkerAccount(
    val portal: String,
    val mac: String,
    val serial: String,
    val deviceId: String
) {
    /** The bare origin, used as the media request origin instead of a browser site. */
    val origin: String get() = StalkerEndpoints.originOf(portal).takeIf { it.startsWith("http") } ?: portal

    val cacheKey: String
        get() {
            val raw = "${portal.trim().lowercase(Locale.ROOT)}|${mac.trim().uppercase(Locale.ROOT)}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

    companion object {
        const val DEFAULT_PORTAL = "http://new.maxtv.be/c/"
        const val DEFAULT_MAC = "00:1A:79:BA:DF:D7"
        const val LEGACY_PORTAL = "http://new.maxtv.be/stalker_portal/c/"
        const val LEGACY_MAC = "00:1A:79:EE:03:9F"
        const val DEFAULT_SERIAL = "92180365D21FE"
        const val DEFAULT_DEVICE = "3610DEAFB556781F84CBC8A6AF876A8F20637668862B28DEECFACE00D67A6070"
        val DEFAULT = StalkerAccount(DEFAULT_PORTAL, DEFAULT_MAC, DEFAULT_SERIAL, DEFAULT_DEVICE)
    }
}

/**
 * The addresses a portal's API may live at.
 *
 * A portal address is the *web client* path — `http://host/stalker_portal/c/` — and that client's
 * `server/load.php` is not necessarily beneath it: most panels keep the API one level up, at
 * `http://host/stalker_portal/server/load.php`, and answer 404 for the nested path. That single wrong
 * guess is what a "didn't load (HTTP 404)" section was: the only address ever tried was the configured
 * one plus `/server/load.php`. The candidates are ordered so the configured address still costs exactly
 * one request when it is right, and so the common panel layout is reached second.
 */
internal object StalkerEndpoints {
    private const val API = "server/load.php"

    fun candidates(portal: String): List<String> {
        val configured = portal.trim().ifBlank { StalkerAccount.DEFAULT_PORTAL }.trimEnd('/')
        val origin = originOf(configured)
        val out = LinkedHashSet<String>()
        fun add(url: String) { if (url.startsWith("http")) out.add(url) }
        // Exactly as configured.
        add("$configured/$API")
        // The web client path is not the API: its load.php sits one level up.
        if (configured.endsWith("/c")) add(configured.removeSuffix("/c") + "/$API")
        add("$configured/portal.php")
        // The same files on the bare host.
        add("$origin/stalker_portal/$API")
        add("$origin/$API")
        add("$origin/portal.php")
        add("$origin/stalker_portal/portal.php")
        // A panel that only answers over TLS.
        if (configured.startsWith("http://")) {
            val secure = "https://" + configured.removePrefix("http://")
            add("$secure/$API")
            if (secure.endsWith("/c")) add(secure.removeSuffix("/c") + "/$API")
        }
        return out.toList()
    }

    fun originOf(url: String): String = runCatching {
        val uri = URI(url.trim())
        "${uri.scheme}://${uri.host}" + (uri.port.takeIf { it > 0 }?.let { ":$it" } ?: "")
    }.getOrElse { url.trim() }.trimEnd('/')

    /** `/stalker_portal/server/load.php` — short enough to quote on a television status line. */
    fun label(url: String): String {
        val origin = originOf(url)
        return url.removePrefix(origin).ifBlank { url }
    }
}

/**
 * A portal answers `get_all_channels` with its whole catalogue in one document - every channel, its
 * command and its logo path. Four megabytes is a website player's budget, not a portal's: the first real
 * catalogue this app asked for was refused with "Player response too large".
 */
internal const val STALKER_CATALOGUE_BYTES = 28 * 1024 * 1024

/** The portal answered, but not with something this app can use. */
class StalkerException(val status: Int? = null, val portalDetail: String = "") : IOException(
    when {
        status != null -> "The portal refused the request (HTTP $status)"
        portalDetail.isNotEmpty() -> portalDetail
        else -> "The portal did not answer with a usable response"
    }
)

/**
 * A Stalker / ministra portal client.
 *
 * The sequence a MAG box performs is reproduced: `handshake` for a token, `get_profile` so the portal ties
 * the token to this device, then `get_all_channels` for the catalogue and `create_link` per channel for a
 * playable URL. The first handshake also finds *where* the API is, and the winning address is remembered
 * so later launches skip that. The channel list is read once per section open and the link once per tune;
 * nothing else is cached, because portal links expire.
 */
internal class StalkerPortal(
    private val account: StalkerAccount = StalkerAccount.DEFAULT,
    private val http: TgTransport = TgHttp(
        readTimeoutSeconds = 25,
        callTimeoutSeconds = 40,
        maxBodyBytes = STALKER_CATALOGUE_BYTES
    ),
    /** An address that already answered for this portal, usually from the last launch. */
    knownEndpoint: String? = null
) {
    companion object {
        /** The ceiling the portal transport is built with, exposed so a test can pin it. */
        const val MAX_CATALOGUE_BYTES = STALKER_CATALOGUE_BYTES
    }

    private var token: String? = null
    private var profiled = false
    private var endpoint: String? = knownEndpoint?.takeIf { it.startsWith("http") }

    /** The address that answered, once one has: persisted so the next launch skips discovery. */
    val workingEndpoint: String? get() = endpoint

    /** The whole catalogue, in the order the portal lists it. */
    fun channels(): List<StalkerChannel> {
        session()
        val body = request("itv", "get_all_channels", emptyMap())
        val list = StalkerProtocol.channels(body)
        if (list.isNotEmpty()) return list
        val explicitError = StalkerProtocol.error(body)
        if (explicitError != null) throw StalkerException(portalDetail = explicitError)
        // Fallback for Stalker panels that disable bulk get_all_channels and serve paginated get_ordered_list.
        val paged = runCatching { fetchOrderedListFallback() }.getOrDefault(emptyList())
        if (paged.isNotEmpty()) return paged
        throw StalkerException(portalDetail = "The portal lists no channels")
    }

    private fun fetchOrderedListFallback(): List<StalkerChannel> {
        val out = ArrayList<StalkerChannel>(512)
        for (page in 1..12) {
            val pageBody = request(
                "itv",
                "get_ordered_list",
                linkedMapOf("genre" to "*", "force_ch_link_check" to "0", "fav" to "0", "sortby" to "number", "hd" to "0", "p" to page.toString())
            )
            val batch = StalkerProtocol.channels(pageBody)
            if (batch.isEmpty()) break
            out.addAll(batch)
            if (batch.size < 14) break
        }
        return out
    }

    /** Genre id to name. Best effort: a portal may refuse it without refusing the channels. */
    fun genres(): Map<String, String> {
        session()
        return runCatching { StalkerProtocol.genres(request("itv", "get_genres", emptyMap())) }.getOrDefault(emptyMap())
    }

    /** The playable URL for one channel handle. */
    fun link(cmd: String): String {
        session()
        val body = request("itv", "create_link", linkedMapOf(
            "cmd" to cmd,
            "series" to "",
            "forced_storage" to "",
            "disable_ad" to "0",
            "download" to "0",
            "force_ch_link_check" to "0"
        ))
        // A portal may hand back the channel's own address unchanged; that is a valid answer too.
        return StalkerProtocol.streamUrl(StalkerProtocol.link(body).orEmpty())
            ?: StalkerProtocol.streamUrl(cmd)
            ?: throw StalkerException(portalDetail = StalkerProtocol.error(body) ?: "The portal returned no playable link for this channel")
    }

    private fun session() {
        if (token == null) handshake()
        if (!profiled) {
            profiled = true
            // Some portals answer get_profile only after a second step they do not advertise; the token is
            // what the catalogue calls need, so a refusal here is not fatal.
            runCatching { profile() }
        }
    }

    private fun handshake() {
        // A known address is handshaked first; if it moved or stopped answering, fall back to full discovery.
        val known = endpoint
        if (known != null) {
            try {
                handshakeAt(known)
                return
            } catch (_: Exception) {
                endpoint = null
            }
        }
        endpoint = discover()
    }

    /**
     * Asks every candidate address for a handshake once and keeps the one that answers with a token. A
     * portal that answers with a refusal of its own (an expired MAC, too many connections) is reported in
     * its own words instead of as a list of HTTP codes.
     */
    private fun discover(): String {
        val failures = ArrayList<String>()
        var spoken: String? = null
        for (candidate in StalkerEndpoints.candidates(account.portal)) {
            checkInterrupted()
            try {
                handshakeAt(candidate)
                return candidate
            } catch (e: StalkerException) {
                if (e.status == null && e.portalDetail.isNotBlank()) spoken = spoken ?: e.portalDetail
                failures.add("${StalkerEndpoints.label(candidate)} ${e.status?.let { "HTTP $it" } ?: "no token"}")
            } catch (e: Exception) {
                // The host itself is silent - DNS, refused, timed out - so its other paths will not help.
                if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
                    e is java.net.ConnectException || e is java.io.InterruptedIOException) throw e
                failures.add("${StalkerEndpoints.label(candidate)} ${e.message ?: e.javaClass.simpleName}")
            }
        }
        val detail = "no portal at this address (tried " + failures.take(4).joinToString(", ") +
            (if (failures.size > 4) " …)" else ")")
        throw StalkerException(portalDetail = spoken ?: detail)
    }

    private fun handshakeAt(base: String) {
        val body = raw(base, "stb", "handshake", linkedMapOf("token" to "", "prehash" to "0"), auth = false)
        val issued = StalkerProtocol.token(body)
            ?: throw StalkerException(portalDetail = StalkerProtocol.error(body) ?: "the portal issued no token")
        token = issued
        profiled = false
    }

    private fun profile() {
        request("stb", "get_profile", linkedMapOf(
            "hd" to "1",
            "auth_second_step" to "1",
            "prehash" to "0",
            "num_banks" to "2",
            "stb_type" to "MAG254",
            "sn" to account.serial,
            "device_id" to account.deviceId,
            "device_id2" to account.deviceId,
            "signature" to "",
            "client_type" to "STB",
            "image_version" to "218",
            "video_out" to "hdmi",
            "hw_version" to "1.7-BD-00",
            "api_signature" to "262"
        ))
    }

    private fun request(type: String, action: String, params: Map<String, String>, auth: Boolean = true, retried: Boolean = false): String {
        val base = endpoint ?: throw StalkerException(portalDetail = "no portal address was reached")
        val body = raw(base, type, action, params, auth)
        // An expired token is normal on a portal that sat idle; handshake once and repeat the call.
        if (auth && !retried && StalkerProtocol.expired(body)) {
            token = null
            handshake()
            return request(type, action, params, auth, retried = true)
        }
        return body
    }

    private fun raw(base: String, type: String, action: String, params: Map<String, String>, auth: Boolean): String {
        checkInterrupted()
        val url = StringBuilder(base).append("?type=").append(type).append("&action=").append(action)
        params.forEach { (key, value) -> url.append('&').append(key).append('=').append(encode(value)) }
        url.append("&JsHttpRequest=1-xml")
        val response = http.get(url.toString(), headers(auth))
        if (response.code != 200) throw StalkerException(response.code)
        return String(response.body, Charsets.UTF_8)
    }

    private fun headers(auth: Boolean): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Cookie"] = "mac=${encode(account.mac)}; stb_lang=en; timezone=Asia/Kolkata"
        headers["User-Agent"] = StalkerProtocol.MAG_USER_AGENT
        headers["X-User-Agent"] = StalkerProtocol.DEVICE_USER_AGENT
        headers["Accept"] = "*/*"
        // The web client path the portal was issued with; independent of where its API turned out to be.
        headers["Referer"] = account.portal.trimEnd('/') + "/"
        if (auth) token?.let { headers["Authorization"] = "Bearer $it" }
        return headers
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
