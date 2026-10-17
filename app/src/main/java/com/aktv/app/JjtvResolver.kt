package com.aktv.app

import org.json.JSONObject
import java.io.IOException

/** Fresh JJTV-owned URLs, keys and cookies for the same V1/Jio channel ID. Never a web-player fallback. */
internal class JjtvResolver(
    private val http: TgTransport = TgHttp(),
    private val site: String = SITE,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object { const val SITE = "https://jjtvxweb.pages.dev" }
    private data class Cached<T>(val until: Long, val value: T)
    private var configuration: Cached<JjtvProtocol.Config>? = null
    private var catalog: Cached<Map<String, JSONObject>>? = null
    private val cookies = HashMap<String, Cached<String>>()
    private val sources = HashMap<String, Cached<List<TgStream>>>()

    @Synchronized
    fun resolve(id: String, fresh: Boolean = false): List<TgStream> {
        require(id.matches(Regex("[0-9]{1,10}"))) { "Invalid Jio channel id" }
        checkInterrupted()
        if (fresh) {
            configuration = null; catalog = null; cookies.clear(); sources.clear()
        } else sources[id]?.takeIf { it.until > now() }?.let { return it.value }

        val page = "$site/pind?id=$id"
        val previousConfig = configuration
        val config = previousConfig?.takeIf { it.until > now() }?.value ?: discover(page).also {
            if (previousConfig?.value != it) { catalog = null; cookies.clear(); sources.clear() }
            configuration = Cached(now() + 5 * 60_000, it)
        }
        val channels = catalog?.takeIf { it.until > now() }?.value ?: JjtvProtocol.catalog(get(config.channels, page)).also {
            catalog = Cached(now() + 30_000, it)
        }
        val channel = channels[id] ?: throw IOException("JJTV has no matching channel ID")
        val urls = JjtvProtocol.urls(channel)
        val hasStar = urls.any(JjtvProtocol::isStarUrl)
        val hasRegular = urls.any { !JjtvProtocol.isStarUrl(it) }
        val star = if (hasStar) cookie(config.starCookies, page) { JjtvProtocol.starCookie(it, id) } else ""
        val regular = if (hasRegular) cookie(config.primaryCookie, page) { JjtvProtocol.regularCookie(it) } else ""
        val fallback = if (hasRegular) cookie(config.fallbackCookie, page) { JjtvProtocol.regularCookie(it, fallback = true) } else ""
        val result = JjtvProtocol.streams(channel, config, regular, fallback, star, page, site)
        checkInterrupted()
        if (result.isEmpty()) throw IOException("JJTV did not supply a current native source for this channel")
        val expiry = result.mapNotNull { StreamRequestPolicy.expiry(it.url, it.cookie) }.minOrNull()
        val usedCookieUrls = listOfNotNull(if (hasStar) config.starCookies else null,
            if (hasRegular) config.primaryCookie else null, if (hasRegular) config.fallbackCookie else null)
        val cookieUntil = usedCookieUrls.mapNotNull { cookies[it]?.until }.minOrNull() ?: Long.MAX_VALUE
        val until = minOf(now() + 30_000, expiry?.minus(10_000) ?: Long.MAX_VALUE,
            catalog?.until ?: Long.MAX_VALUE, cookieUntil)
        if (until > now()) {
            if (sources.size >= 128) sources.entries.removeAll { it.value.until <= now() }
            if (sources.size >= 128) sources.clear()
            sources[id] = Cached(until, result)
        }
        return result
    }

    private fun discover(page: String): JjtvProtocol.Config {
        val html = get("$page&_aktv=${now()}", page)
        JjtvProtocol.config(html, page)?.let { return it }
        // Follow only same-site player assets. Do not fetch Shaka, adverts, trackers or a different embed.
        val pending = LinkedHashSet(TgDiscovery.scripts(html, page, site))
        val seen = HashSet<String>()
        var code = html
        while (pending.isNotEmpty() && seen.size < 8) {
            checkInterrupted()
            val url = pending.first(); pending.remove(url); seen.add(url)
            val script = optional { get(url, page) } ?: continue
            require(code.length + script.length <= TgProtocol.MAX_BODY) { "Player scripts too large" }
            code += "\n$script"
            JjtvProtocol.config(code, page)?.let { return it }
            pending.addAll(TgDiscovery.scripts(script, url, site).filter { it !in seen })
        }
        throw IOException("JJTV's current native player configuration could not be read")
    }

    private fun cookie(url: String?, page: String, select: (String?) -> String): String {
        if (url == null) return ""
        val cached = cookies[url]?.takeIf { it.until > now() }
        var body = cached?.value ?: optional { get(url, page) }
        var reused = cached != null
        var value = select(body)
        var expiry = StreamRequestPolicy.expiry("", value)
        // Different PL channels in the same JSON can expire at different times. Refresh the endpoint
        // once when this channel's credential is absent/near expiry; never substitute another channel.
        if (cached != null && (value.isBlank() || (expiry != null && expiry <= now() + 10_000))) {
            body = optional { get(url, page) }
            reused = false
            value = select(body)
            expiry = StreamRequestPolicy.expiry("", value)
        }
        val until = minOf(if (reused) requireNotNull(cached).until else now() + 30_000,
            expiry?.minus(10_000) ?: Long.MAX_VALUE)
        if (body != null && value.isNotBlank() && until > now()) cookies[url] = Cached(until, body)
        else cookies.remove(url)
        return value
    }

    private fun get(url: String, page: String): String {
        checkInterrupted()
        val response = http.get(url, linkedMapOf(
            "Origin" to site, "Referer" to page,
            "Accept" to "application/json, text/html, application/javascript",
            "Cache-Control" to "no-cache, no-store", "Pragma" to "no-cache"
        ))
        checkInterrupted()
        if (response.code !in 200..299) throw IOException("JJTV request failed (HTTP ${response.code})")
        require(response.body.size <= TgProtocol.MAX_BODY) { "JJTV response too large" }
        return response.body.toString(Charsets.UTF_8)
    }

    private fun <T> optional(block: () -> T): T? = try { block() } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        checkInterrupted()
        null
    }
}
