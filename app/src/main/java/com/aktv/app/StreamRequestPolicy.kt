package com.aktv.app

import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** Session-local auth: signed query parameters are never copied to a different origin or over relay queries. */
class StreamRequestPolicy(private val stream: TgStream) {
    data class Request(val url: String, val headers: Map<String, String>)
    private val origin = originOf(stream.url)
    private val tokens = ConcurrentHashMap<String, String>()
    private val manifests = ConcurrentHashMap.newKeySet<String>().apply { add(resource(stream.url)) }
    private val providerChannelRoots = ConcurrentHashMap<String, String>()
    private val websiteAuth = stream.requestOrigin in setOf(TgResolver.SITE, JjtvResolver.SITE) && cookieToken(stream.cookie) != null
    // TG's player explicitly excludes its allinonereborn relay from the Jio query filter.
    // The relay owns its query/authentication, even when TG repeats the primary cookie on it.
    private val preserveRelayQuery = stream.requestOrigin == TgResolver.SITE
    private val suppliedToken = cookieToken(stream.cookie)?.takeUnless { preserveRelayQuery && ownsQuery(stream.url) }

    init {
        (suppliedToken ?: tokenOf(stream.url))?.let { tokens[origin] = it }
        if (websiteAuth && suppliedToken != null) channelRoot(stream.url)?.let { providerChannelRoots[origin] = it }
    }

    fun request(url: String): Request {
        val target = originOf(url)
        // The CDN, not a potentially wrong TV clock, decides whether a credential is valid. Never
        // silently turn an authenticated request into an unsigned one when the local date changes.
        val authRoot = providerChannelRoots[target]
        val requestRoot = channelRoot(url)
        val token = tokens[target]?.takeUnless {
            (preserveRelayQuery && ownsQuery(url)) || (authRoot != null && requestRoot != null && authRoot != requestRoot)
        }
        val resolved = when {
            token == null -> url
            (url.substringBefore('#') == stream.url.substringBefore('#') && suppliedToken != null) ||
                (authRoot != null && requestRoot == authRoot) -> withToken(url, token, replace = true)
            else -> withToken(url, token, replace = false)
        }
        val headers = linkedMapOf("Origin" to stream.requestOrigin.trimEnd('/'))
        if (!stream.omitReferrer) headers["Referer"] = stream.requestOrigin.trimEnd('/') + "/"
        // Headers supplied for one relay must not become the credentials of its redirect/segment host.
        if (target == origin) stream.headers.forEach { (key, value) ->
            if (stream.omitReferrer && key.equals("Referer", true)) return@forEach
            headers.keys.firstOrNull { it.equals(key, true) }?.let { headers.remove(it) }
            if (key.lowercase() !in setOf("host", "connection", "content-length", "accept-encoding") &&
                key.none { it == '\r' || it == '\n' } && value.none { it == '\r' || it == '\n' }) headers[key] = value
        }
        return Request(resolved, headers)
    }

    /** Successful manifests/playlists can renew auth. HLS child playlists may introduce a signed
     * CDN of their own. An independently signed audio/video segment must never overwrite session auth.
     */
    fun observed(requestedUrl: String, finalUrl: String, childPlaylist: Boolean = false) {
        if (resource(requestedUrl) !in manifests && !(stream.type == "hls" && childPlaylist)) return
        manifests.add(resource(finalUrl))
        tokenOf(finalUrl)?.let {
            val target = originOf(finalUrl)
            tokens[target] = it
            // TG/JJTV actively replace authentication on every request in the advertised Jio
            // channel. Learn that channel scope only from an authorized successful manifest.
            if (websiteAuth) channelRoot(finalUrl)?.let { root -> providerChannelRoots[target] = root }
        }
    }

    companion object {
        private const val TOKEN = "__hdnea__"
        private fun channelRoot(url: String): String? = try {
            val uri = URI(url)
            val parts = uri.rawPath.orEmpty().split('/')
            if (uri.scheme != "https" || uri.host?.lowercase() !in setOf("jiotvmblive.cdn.jio.com", "jiotvpllive.cdn.jio.com") ||
                uri.port !in setOf(-1, 443) || parts.size < 4 || parts[1] != "bpk-tv" ||
                !parts[2].matches(Regex("[A-Za-z0-9_-]+")) || parts.any { it == "." || it == ".." }) null
            else "/bpk-tv/${parts[2]}/"
        } catch (_: Exception) { null }
        private fun resource(url: String): String = try {
            val uri = URI(url)
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && it.substringBefore('=') != TOKEN }.joinToString("&")
            originOf(url) + uri.rawPath.orEmpty() + if (query.isEmpty()) "" else "?$query"
        } catch (_: Exception) { url.substringBefore('#') }
        private fun ownsQuery(url: String): Boolean = try {
            URI(url).host.orEmpty().contains("allinonereborn", ignoreCase = true)
        } catch (_: Exception) { false }
        fun originOf(url: String): String = try {
            val u = URI(url)
            val scheme = u.scheme.orEmpty().lowercase()
            val port = if (u.port != -1) u.port else if (scheme == "https") 443 else 80
            "$scheme://${u.host.orEmpty().lowercase()}:$port"
        } catch (_: Exception) { "" }

        fun tokenOf(url: String): String? = try {
            URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == TOKEN }?.substringAfter('=')?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

        private fun cookieToken(cookie: String): String? = Regex("(?:^|[?;&\\s])__hdnea__=([^;\\s&]+)")
            .find(cookie.trim())?.groupValues?.get(1)

        fun expiry(url: String, cookie: String = ""): Long? {
            val token = cookieToken(cookie) ?: tokenOf(url) ?: return null
            return tokenTime(token, "exp")
        }
        fun notBefore(url: String, cookie: String = ""): Long? {
            val token = cookieToken(cookie) ?: tokenOf(url) ?: return null
            return tokenTime(token, "st")
        }
        private fun tokenTime(token: String, field: String): Long? = try {
            Regex("(?:^|~)$field=(\\d+)(?:~|$)").find(URLDecoder.decode(token, "UTF-8"))?.groupValues?.get(1)?.toLongOrNull()?.let {
                if (it > Long.MAX_VALUE / 1000) null else it * 1000
            }
        } catch (_: Exception) { null }

        /** Work on the raw query: re-encoding an already signed URL can invalidate its signature. */
        private fun withToken(url: String, token: String, replace: Boolean): String {
            val fragment = if ('#' in url) "#${url.substringAfter('#')}" else ""
            val base = url.substringBefore('#')
            val query = base.substringAfter('?', "").split('&').filter { it.isNotBlank() }
            if (!replace && query.any { it.substringBefore('=') == TOKEN }) return url
            val parts = query.filterNot { it.substringBefore('=') == TOKEN } + "$TOKEN=$token"
            return base.substringBefore('?') + "?" + parts.joinToString("&") + fragment
        }
    }
}
