package com.aktv.app

import java.io.IOException
import java.net.URI
import java.net.URLEncoder

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
    val origin: String get() = runCatching {
        val uri = URI(portal)
        "${uri.scheme}://${uri.host}" + (uri.port.takeIf { it > 0 }?.let { ":$it" } ?: "")
    }.getOrDefault("").takeIf { it.startsWith("http") } ?: portal

    companion object {
        const val DEFAULT_PORTAL = "http://new.maxtv.be/stalker_portal/c/"
        const val DEFAULT_MAC = "00:1A:79:EE:03:9F"
        const val DEFAULT_SERIAL = "92180365D21FE"
        const val DEFAULT_DEVICE = "3610DEAFB556781F84CBC8A6AF876A8F20637668862B28DEECFACE00D67A6070"
        val DEFAULT = StalkerAccount(DEFAULT_PORTAL, DEFAULT_MAC, DEFAULT_SERIAL, DEFAULT_DEVICE)
    }
}

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
 * playable URL. The channel list is fetched once per section open and the link once per tune; nothing here
 * is cached beyond the session token, because portal links expire.
 */
internal class StalkerPortal(
    private val account: StalkerAccount = StalkerAccount.DEFAULT,
    private val http: TgTransport = TgHttp(readTimeoutSeconds = 20, callTimeoutSeconds = 30)
) {
    private val base = account.portal.trimEnd('/') + "/"
    private var token: String? = null
    private var profiled = false

    /** The whole catalogue, in the order the portal lists it. */
    fun channels(): List<StalkerChannel> {
        session()
        val body = call("itv", "get_all_channels")
        val list = StalkerProtocol.channels(body)
        if (list.isEmpty()) throw StalkerException(portalDetail = StalkerProtocol.error(body) ?: "The portal lists no channels")
        return list
    }

    /** Genre id to name. Best effort: a portal may refuse it without refusing the channels. */
    fun genres(): Map<String, String> {
        session()
        return runCatching { StalkerProtocol.genres(call("itv", "get_genres")) }.getOrDefault(emptyMap())
    }

    /** The playable URL for one channel handle. */
    fun link(cmd: String): String {
        session()
        val body = call("itv", "create_link", linkedMapOf(
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

    private fun handshake(): String {
        val body = call("stb", "handshake", linkedMapOf("token" to "", "prehash" to "0"), auth = false)
        val issued = StalkerProtocol.token(body)
            ?: throw StalkerException(portalDetail = StalkerProtocol.error(body) ?: "The portal issued no token")
        token = issued
        profiled = false
        return issued
    }

    private fun profile() {
        call("stb", "get_profile", linkedMapOf(
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

    private fun call(
        type: String,
        action: String,
        params: Map<String, String> = emptyMap(),
        auth: Boolean = true,
        retried: Boolean = false
    ): String {
        checkInterrupted()
        val url = StringBuilder(base).append("server/load.php?type=").append(type).append("&action=").append(action)
        params.forEach { (key, value) -> url.append('&').append(key).append('=').append(encode(value)) }
        url.append("&JsHttpRequest=1-xml")
        val response = http.get(url.toString(), headers(auth))
        if (response.code != 200) throw StalkerException(response.code)
        val body = String(response.body, Charsets.UTF_8)
        // An expired token is normal on a portal that sat idle; handshake once and repeat the call.
        if (auth && !retried && StalkerProtocol.expired(body)) {
            token = null
            handshake()
            return call(type, action, params, auth, retried = true)
        }
        return body
    }

    private fun headers(auth: Boolean): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Cookie"] = "mac=${encode(account.mac)}; stb_lang=en; timezone=Europe/London"
        headers["User-Agent"] = StalkerProtocol.MAG_USER_AGENT
        headers["X-User-Agent"] = StalkerProtocol.DEVICE_USER_AGENT
        headers["Accept"] = "*/*"
        headers["Referer"] = base + "c/"
        if (auth) token?.let { headers["Authorization"] = "Bearer $it" }
        return headers
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
