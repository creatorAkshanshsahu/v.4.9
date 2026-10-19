package com.aktv.app

import org.json.JSONObject
import java.io.IOException

internal class JjtvHttpException(val status: Int, val stage: JjtvLookupStage = JjtvLookupStage.UNKNOWN,
    val reply: HttpReply? = null) : IOException("JJTV ${stage.label}: ${reply?.describe() ?: "HTTP $status"}")

internal class JjtvDataException(val stage: JjtvLookupStage, val reply: HttpReply? = null) :
    IOException("JJTV ${stage.label}: response is not usable player metadata${reply?.let { "; ${it.describe()}" }.orEmpty()}")

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
    private val replies = java.util.EnumMap<JjtvLookupStage, HttpReply>(JjtvLookupStage::class.java)

    @Synchronized
    fun resolve(id: String, fresh: Boolean = false): List<TgStream> {
        require(id.matches(Regex("[0-9]{1,10}"))) { "Invalid Jio channel id" }
        checkInterrupted()
        lastCookieFailure = null; replies.clear()
        if (fresh) {
            configuration = null; catalog = null; cookies.clear(); sources.clear()
        } else sources[id]?.takeIf { it.until > elapsed() }?.let { return it.value }

        val page = "$site/pind?id=$id"
        val previousConfig = configuration
        val config = previousConfig?.takeIf { it.until > elapsed() }?.value ?: discover(page).also {
            if (previousConfig?.value != it) { catalog = null; cookies.clear(); sources.clear() }
            configuration = Cached(elapsed() + 5 * 60_000, it)
        }
        val channels = catalog?.takeIf { it.until > elapsed() }?.value ?: readCatalog(config.channels).also {
            catalog = Cached(elapsed() + 30_000, it)
        }
        val channel = channels[id] ?: throw IOException("JJTV has no matching channel ID")
        val urls = JjtvProtocol.urls(channel)
        val hasStar = urls.any(JjtvProtocol::isStarUrl)
        val hasRegular = urls.any { !JjtvProtocol.isStarUrl(it) }
        val star = if (hasStar) cookie(config.starCookies, JjtvLookupStage.STAR_COOKIE) { JjtvProtocol.starCookie(it, id, tokenClock.now()) } else ""
        val regular = if (hasRegular) cookie(config.primaryCookie, JjtvLookupStage.PRIMARY_COOKIE) { JjtvProtocol.regularCookie(it, now = tokenClock.now()) } else ""
        val fallback = if (hasRegular) cookie(config.fallbackCookie, JjtvLookupStage.FALLBACK_COOKIE) { JjtvProtocol.regularCookie(it, fallback = true, now = tokenClock.now()) } else ""
        val advertised = JjtvProtocol.streams(channel, config, regular, fallback, star, page, site)
            .flatMap(JjtvRequestProfiles::variants)
        val result = try { CredentialFreshness.usable(LiveProvider.JJTV, advertised, tokenClock) }
            catch (error: SourceCredentialsUnavailableException) { throw lastCookieFailure ?: error }
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
        val html = get(page, JjtvLookupStage.PAGE)
        JjtvProtocol.config(html, page)?.let { return it }
        // Follow only same-site player assets. Do not fetch Shaka, adverts, trackers or a different embed.
        val pending = LinkedHashSet(TgDiscovery.scripts(html, page, site))
        val seen = HashSet<String>()
        var code = html
        var scriptFailure: Exception? = null
        while (pending.isNotEmpty() && seen.size < 8) {
            checkInterrupted()
            val url = pending.first(); pending.remove(url); seen.add(url)
            val script = try { get(url, JjtvLookupStage.SCRIPT) } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                checkInterrupted(); scriptFailure = error; continue
            }
            require(code.length + script.length <= TgProtocol.MAX_BODY) { "Player scripts too large" }
            code += "\n$script"
            JjtvProtocol.config(code, page)?.let { return it }
            pending.addAll(TgDiscovery.scripts(script, url, site).filter { it !in seen })
        }
        throw scriptFailure ?: JjtvDataException(JjtvLookupStage.PAGE, replies[JjtvLookupStage.PAGE])
    }

    private fun cookie(url: String?, stage: JjtvLookupStage, select: (String?) -> String): String {
        if (url == null) return ""
        val cached = cookies[url]?.takeIf { it.until > elapsed() }
        fun fetch(): String? = try { get(url, stage) } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            checkInterrupted(); lastCookieFailure = error; null
        }
        var body = cached?.value ?: fetch()
        var reused = cached != null
        var value = select(body)
        var expiry = StreamRequestPolicy.expiry("", value)
        // Different PL channels in the same JSON can expire at different times. Refresh the endpoint
        // once when this channel's credential is absent/near expiry; never substitute another channel.
        if ((cached != null && value.isBlank()) || (expiry != null && expiry <= tokenClock.now() + 10_000) ||
            (StreamRequestPolicy.notBefore("", value)?.let { it > tokenClock.now() + 10_000 } == true)) {
            body = fetch()
            reused = false
            value = select(body)
            expiry = StreamRequestPolicy.expiry("", value)
        }
        if (body != null && value.isBlank() && lastCookieFailure == null)
            lastCookieFailure = JjtvDataException(stage, replies[stage])
        val until = minOf(if (reused) requireNotNull(cached).until else elapsed() + 30_000,
            expiry?.let { elapsed() + (it - tokenClock.now() - 10_000) } ?: Long.MAX_VALUE)
        if (body != null && value.isNotBlank() && until > elapsed()) cookies[url] = Cached(until, body)
        else cookies.remove(url)
        return value
    }

    private fun readCatalog(url: String): Map<String, JSONObject> {
        val body = get(url, JjtvLookupStage.CATALOGUE)
        return try { JjtvProtocol.catalog(body) } catch (_: Exception) {
            throw JjtvDataException(JjtvLookupStage.CATALOGUE,
                replies[JjtvLookupStage.CATALOGUE])
        }
    }

    private fun get(url: String, stage: JjtvLookupStage): String {
        checkInterrupted()
        val response = http.get(JjtvMetadataRequests.request(url, site, stage))
        checkInterrupted()
        tokenClock.observe(response.serverTimeMs)
        val reply = response.reply ?: HttpReply.of(response.code, response.contentType, response.body)
        replies[stage] = reply
        if (response.code !in 200..299) throw JjtvHttpException(response.code, stage, reply)
        require(response.body.size <= TgProtocol.MAX_BODY) { "JJTV response too large" }
        if (reply.challenge) throw JjtvDataException(stage, reply)
        if (stage !in setOf(JjtvLookupStage.PAGE, JjtvLookupStage.SCRIPT) &&
            reply.body in setOf(HttpReply.Body.HTML, HttpReply.Body.XML_ERROR, HttpReply.Body.EMPTY))
            throw JjtvDataException(stage, reply)
        return response.body.toString(Charsets.UTF_8)
    }
}
