package com.aktv.app

import org.json.JSONObject
import java.io.IOException

internal class JjtvHttpException(val status: Int) : IOException("JJTV metadata HTTP $status")

/** Fresh JJTV-owned URLs, keys and cookies for the same V1/Jio channel ID. Never a web-player fallback. */
internal class JjtvResolver(
    private val http: TgTransport = TgHttp(),
    private val site: String = SITE,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object { const val SITE = "https://jjtvxweb.pages.dev" }
    private data class Cached<T>(val until: Long, val value: T)
    private var configuration: Cached<JjtvProtocol.Config>? = null
    private var catalog: Cached<Map<String, JSONObject>>? = null
    private val cookies = HashMap<String, Cached<String>>()
    private val sources = HashMap<String, Cached<List<TgStream>>>()
    private val tokenClock = HttpReferenceClock(now, elapsed)
    private var lastCookieFailure: Exception? = null
    private var nonce = 0L

    @Synchronized
    fun resolve(id: String, fresh: Boolean = false): List<TgStream> {
        require(id.matches(Regex("[0-9]{1,10}"))) { "Invalid Jio channel id" }
        checkInterrupted()
        lastCookieFailure = null
        if (fresh) {
            configuration = null; catalog = null; cookies.clear(); sources.clear()
        } else sources[id]?.takeIf { it.until > elapsed() }?.let { return it.value }

        val page = "$site/pind?id=$id"
        val previousConfig = configuration
        val config = previousConfig?.takeIf { it.until > elapsed() }?.value ?: discover(page).also {
            if (previousConfig?.value != it) { catalog = null; cookies.clear(); sources.clear() }
            configuration = Cached(elapsed() + 5 * 60_000, it)
        }
        val channels = catalog?.takeIf { it.until > elapsed() }?.value ?: JjtvProtocol.catalog(get(config.channels, page, bypassCache = fresh)).also {
            catalog = Cached(elapsed() + 30_000, it)
        }
        val channel = channels[id] ?: throw IOException("JJTV has no matching channel ID")
        val urls = JjtvProtocol.urls(channel)
        val hasStar = urls.any(JjtvProtocol::isStarUrl)
        val hasRegular = urls.any { !JjtvProtocol.isStarUrl(it) }
        val star = if (hasStar) cookie(config.starCookies, page, fresh) { JjtvProtocol.starCookie(it, id, tokenClock.now()) } else ""
        val regular = if (hasRegular) cookie(config.primaryCookie, page, fresh) { JjtvProtocol.regularCookie(it, now = tokenClock.now()) } else ""
        val fallback = if (hasRegular) cookie(config.fallbackCookie, page, fresh) { JjtvProtocol.regularCookie(it, fallback = true, now = tokenClock.now()) } else ""
        val result = JjtvProtocol.streams(channel, config, regular, fallback, star, page, site)
            .flatMap(JjtvRequestProfiles::variants)
        checkInterrupted()
        if (result.isEmpty()) throw lastCookieFailure ?: IOException("JJTV did not supply a current native source for this channel")
        val expiry = result.mapNotNull { StreamRequestPolicy.expiry(it.url, it.cookie) }.minOrNull()
        val usedCookieUrls = listOfNotNull(if (hasStar) config.starCookies else null,
            if (hasRegular) config.primaryCookie else null, if (hasRegular) config.fallbackCookie else null)
        val cookieUntil = usedCookieUrls.mapNotNull { cookies[it]?.until }.minOrNull() ?: Long.MAX_VALUE
        val until = minOf(elapsed() + 30_000, expiry?.let { elapsed() + (it - tokenClock.now() - 10_000) } ?: Long.MAX_VALUE,
            catalog?.until ?: Long.MAX_VALUE, cookieUntil)
        if (until > elapsed()) {
            if (sources.size >= 128) sources.entries.removeAll { it.value.until <= elapsed() }
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

    private fun cookie(url: String?, page: String, fresh: Boolean, select: (String?) -> String): String {
        if (url == null) return ""
        val cached = cookies[url]?.takeIf { it.until > elapsed() }
        fun fetch(bypass: Boolean): String? = try { get(url, page, bypassCache = bypass) } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            checkInterrupted(); lastCookieFailure = error; null
        }
        var body = cached?.value ?: fetch(fresh)
        var reused = cached != null
        var value = select(body)
        var expiry = StreamRequestPolicy.expiry("", value)
        // Different PL channels in the same JSON can expire at different times. Refresh the endpoint
        // once when this channel's credential is absent/near expiry; never substitute another channel.
        if ((cached != null && value.isBlank()) || (expiry != null && expiry <= tokenClock.now() + 10_000)) {
            body = fetch(true)
            reused = false
            value = select(body)
            expiry = StreamRequestPolicy.expiry("", value)
        }
        val until = minOf(if (reused) requireNotNull(cached).until else elapsed() + 30_000,
            expiry?.let { elapsed() + (it - tokenClock.now() - 10_000) } ?: Long.MAX_VALUE)
        if (body != null && value.isNotBlank() && until > elapsed()) cookies[url] = Cached(until, body)
        else cookies.remove(url)
        return value
    }

    private fun get(url: String, page: String, bypassCache: Boolean = false): String {
        checkInterrupted()
        // Only unsigned public metadata URLs get a cache-buster. Never change a signed media or
        // metadata query; Cache-Control still applies to endpoints that already own their query.
        val requestUrl = if (bypassCache && java.net.URI(url).rawQuery.isNullOrEmpty())
            "$url?_aktv_refresh=${now()}_${++nonce}" else url
        val response = http.get(requestUrl, linkedMapOf(
            "Origin" to site, "Referer" to page,
            "Accept" to "application/json, text/html, application/javascript",
            "Cache-Control" to "no-cache, no-store", "Pragma" to "no-cache"
        ))
        checkInterrupted()
        tokenClock.observe(response.serverTimeMs)
        if (response.code !in 200..299) throw JjtvHttpException(response.code)
        require(response.body.size <= TgProtocol.MAX_BODY) { "JJTV response too large" }
        return response.body.toString(Charsets.UTF_8)
    }

    private fun <T> optional(block: () -> T): T? = try { block() } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        checkInterrupted()
        null
    }
}
