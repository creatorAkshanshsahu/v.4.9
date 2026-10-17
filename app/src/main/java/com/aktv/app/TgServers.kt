package com.aktv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The server list the TG TV website's own player gets (whatever the site calls them: Server 1, 2, 3 ...),
 * fetched live with fresh tokens and every ClearKey key.
 *
 * Nothing here depends on a particular server number any more. Every stream the site hands out is treated the
 * same way: cookie token applied, all keys attached, keyed + tokened streams tried first. If the site changes the
 * shape of its answer, or redeploys (new function id), the app works that out by itself.
 */
object TgServers {
    class Server(val label: String, val url: String, val drm: JSONObject?) {
        val hasKeys get() = drm != null
    }

    private const val SITE = "https://livetgtv.lovable.app"
    /** Last resort player page, used when the website's own servers all fail. */
    const val ALT_SITE = "https://jjtvxweb.pages.dev"
    private const val FN = "211ba18c9fcbaf4e68e5bb262905070b60b7ed4ffe802d16fbbed00150f1c024"

    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var fn = FN
    @Volatile private var lastDiscovery = 0L
    @Volatile private var lastOk = 0L
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Server>>>()
    private const val CACHE_MS = 90_000L
    private val pool = Executors.newFixedThreadPool(8) { r -> Thread(r).apply { isDaemon = true } }

    /** Remembers the site's function id between launches, so a redeploy is only ever re-discovered once. */
    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("aktv_tg", Context.MODE_PRIVATE)
        prefs?.getString("fn", null)?.takeIf { it.length == 64 }?.let { fn = it }
    }

    // ---------- the site's serialized answer -> plain JSON ----------
    private fun dec(n: Any?): Any? {
        if (n !is JSONObject) return null
        return when (n.optInt("t", -1)) {
            0 -> n.opt("s")
            1 -> n.optString("s")
            2 -> when (n.optInt("s")) { 2 -> true; 3 -> false; else -> null }
            9 -> { val a = n.optJSONArray("a") ?: JSONArray(); val out = JSONArray(); for (i in 0 until a.length()) out.put(dec(a.opt(i)) ?: JSONObject.NULL); out }
            10, 11 -> {
                val p = n.optJSONObject("p"); val o = JSONObject()
                if (p != null) { val k = p.optJSONArray("k"); val v = p.optJSONArray("v")
                    if (k != null && v != null) for (i in 0 until k.length()) o.put(k.optString(i), dec(v.opt(i)) ?: JSONObject.NULL) }
                o
            }
            else -> null
        }
    }

    /**
     * Exactly what the website's player does for every server: when the site hands out a cookie token, the link's own
     * query is thrown away and replaced by that token (the player does the same on every segment, see Streams.factory).
     */
    private fun withCookie(u: String, cookie: String): String {
        val clean = u.replace(Regex("(?<!:)//+"), "/")
        val c = cookie.trim().removePrefix("?")
        if (!c.contains("__hdnea__=")) return clean
        return clean.substringBefore("?") + "?" + c
    }

    private fun keyPair(arr: JSONArray, kid: String, key: String) {
        val a = kid.trim(); val b = key.trim()
        if (a.isNotEmpty() && b.isNotEmpty() && a != "null" && b != "null") arr.put(JSONObject().put("keyId", a).put("key", b))
    }

    /** Every key format the site has used: clearKeys {kid:key}, keyId+key, keys [..], license_key "kid:key,kid:key". */
    private fun drmOf(o: JSONObject): JSONObject? {
        val arr = JSONArray()
        o.optJSONObject("clearKeys")?.let { ck -> ck.keys().forEach { k -> keyPair(arr, k, ck.optString(k)) } }
        o.optJSONObject("clearkeys")?.let { ck -> ck.keys().forEach { k -> keyPair(arr, k, ck.optString(k)) } }
        keyPair(arr, o.optString("keyId", o.optString("kid", "")), o.optString("key", ""))
        o.optJSONArray("keys")?.let { ks -> for (i in 0 until ks.length()) ks.optJSONObject(i)?.let { x ->
            keyPair(arr, x.optString("keyId", x.optString("kid", "")), x.optString("key", x.optString("k", ""))) } }
        for (name in listOf("license_key", "licenseKey", "license")) {
            val lic = o.optString(name, "")
            if (lic.contains(":") && !lic.startsWith("http")) lic.split(",").forEach { p -> if (p.contains(":")) keyPair(arr, p.substringBefore(":"), p.substringAfter(":")) }
        }
        return if (arr.length() == 0) null else JSONObject().put("type", "clearkey").put("keys", arr)
    }

    private val URL_KEYS = listOf("url", "playable", "stream", "src", "link", "manifest", "mpd", "m3u8", "hls", "dash")

    /** Streams one server object offers: `urls` array, or a single link under any of the usual names. */
    private fun urlsOf(o: JSONObject): List<String> {
        val out = ArrayList<String>()
        o.optJSONArray("urls")?.let { a -> for (i in 0 until a.length()) a.optString(i, "").let { if (it.startsWith("http")) out.add(it) } }
        if (out.isEmpty()) for (k in URL_KEYS) o.optString(k, "").let { if (it.startsWith("http")) { out.add(it); return out } }
        return out
    }

    private class Raw(val urls: List<String>, val cookie: String, val drm: JSONObject?)

    /** Walks the whole answer: any object that carries stream links is a server, wherever the site nests it. */
    private fun collect(node: Any?, out: ArrayList<Raw>, depth: Int = 0) {
        if (depth > 6) return
        when (node) {
            is JSONObject -> {
                val urls = urlsOf(node)
                if (urls.isNotEmpty()) out.add(Raw(urls, node.optString("cookie", "").let { if (it == "null") "" else it }, drmOf(node)))
                node.keys().forEach { k ->
                    if (k == "urls" || k == "clearKeys" || k == "clearkeys" || k == "keys" || k == "epg") return@forEach
                    val v = node.opt(k); if (v is JSONObject || v is JSONArray) collect(v, out, depth + 1)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, depth + 1)
        }
    }

    /**
     * One method for every server: a server the site sends without its own cookie token or keys borrows the ones the
     * other servers of the same channel carry (that is the same stream on the same CDN), so Server 1, 2, 3 ... all play
     * the way the website's own player plays them. Labels follow the site's numbering (Server 1, Server 2 ...).
     */
    private fun build(raw: List<Raw>): List<Server> {
        val sharedCookie = raw.firstOrNull { it.cookie.contains("__hdnea__=") }?.cookie ?: ""
        val keys = JSONArray()
        raw.forEach { r -> r.drm?.optJSONArray("keys")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { k ->
            var dup = false; for (j in 0 until keys.length()) if (keys.getJSONObject(j).optString("keyId") == k.optString("keyId")) dup = true
            if (!dup) keys.put(k) } } }
        val sharedDrm = if (keys.length() == 0) null else JSONObject().put("type", "clearkey").put("keys", keys)
        val out = ArrayList<Server>()
        raw.forEachIndexed { i, r ->
            val cookie = r.cookie.ifEmpty { sharedCookie }
            r.urls.forEach { u ->
                val full = withCookie(u, cookie)
                if (out.none { it.url == full }) out.add(Server("Server ${i + 1}", full, sharedDrm ?: r.drm))
            }
        }
        return out
    }

    // ---------- network ----------
    private fun get(u: String, referer: String? = null, timeout: Int = 7000): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", Streams.CHROME)
        if (referer != null) c.setRequestProperty("Referer", referer)
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else { c.disconnect(); null }
    } catch (_: Exception) { null }

    private fun abs(u: String) = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> SITE + u
        else -> "$SITE/$u"
    }

    /** Server-function ids (64 hex characters) found in the site's page and scripts, most likely ones first. Scripts are read in parallel. */
    private fun discover(id: String): List<String> {
        val html = get("$SITE/watch/$id") ?: get(SITE) ?: return emptyList()
        val tagged = Regex("_serverFn/([0-9a-f]{64})"); val loose = Regex("\"([0-9a-f]{64})\"")
        val found = LinkedHashSet<String>()
        tagged.findAll(html).forEach { found.add(it.groupValues[1]) }
        val scripts = Regex("(?:src|href)=\"([^\"]+\\.js)\"").findAll(html).map { abs(it.groupValues[1]) }.distinct().take(14).toList()
        val bodies = scripts.map { u -> pool.submit(Callable { get(u) }) }.mapNotNull { try { it.get(10, TimeUnit.SECONDS) } catch (_: Exception) { null } }
        val loosely = LinkedHashSet<String>()
        for (js in bodies) { tagged.findAll(js).forEach { found.add(it.groupValues[1]) }; loose.findAll(js).forEach { loosely.add(it.groupValues[1]) } }
        found.addAll(loosely)
        return found.take(16)
    }

    private fun call(fnId: String, id: String): List<Server> {
        val inner = """{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["id"],"v":[{"t":1,"s":${JSONObject.quote(id)}}]},"o":0}]},"o":0}"""
        val payload = URLEncoder.encode("""{"t":$inner,"f":127,"m":[]}""", "UTF-8")
        val c = URL("$SITE/_serverFn/$fnId?payload=$payload").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 7000
        c.setRequestProperty("User-Agent", Streams.CHROME)
        c.setRequestProperty("Accept", "application/x-tss-framed, application/x-ndjson, application/json")
        c.setRequestProperty("x-tsr-serverfn", "true")
        c.setRequestProperty("Referer", "$SITE/watch/$id")
        c.setRequestProperty("Origin", SITE)   // the site now refuses (403) calls that do not say they come from it
        c.setRequestProperty("Sec-Fetch-Site", "same-origin"); c.setRequestProperty("Sec-Fetch-Mode", "cors")
        if (c.responseCode !in 200..299) { c.disconnect(); return emptyList() }
        lastOk = System.currentTimeMillis()
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val line = JSONObject(body.lineSequence().first { it.trim().startsWith("{") })
        // the site's serialized form, or plain JSON if it ever stops using it
        val root = (dec(line) as? JSONObject) ?: line
        val raw = ArrayList<Raw>()
        collect(root.optJSONObject("result") ?: root, raw)
        val out = build(raw)
        // streams with keys and a fresh token first (that is what made Star Sports work), site order otherwise
        return out.sortedBy { if (it.hasKeys && it.url.contains("__hdnea__")) 0 else if (it.hasKeys) 1 else 2 }
    }

    private fun tryFn(fnId: String, id: String): List<Server> = try { call(fnId, id) } catch (_: Exception) { emptyList() }

    /**
     * Every server the website offers for [id], fresh tokens and keys included. Answers are kept 90 s so zapping and
     * previews are instant; [fresh] skips that. If the old function id stopped answering (site redeployed), the new one
     * is found among the site's scripts, tested in parallel and remembered.
     */
    /** Re-reads the website's scripts in the background so a site change is picked up without an app update. */
    /** Loads jjtvxweb's channel list and both tokens in the background, so the first channel opens instantly. */
    fun warmAlt() { pool.execute { try { altChannel(""); primaryCookie(); starCookie("") } catch (_: Exception) {} } }

    fun refreshSite() { pool.execute { try {
        val ids = discover("1106"); val good = ids.firstOrNull { c -> tryFn(c, "1106").isNotEmpty() }
        if (good != null && good != fn) { fn = good; prefs?.edit()?.putString("fn", fn)?.apply(); cache.clear() }
    } catch (_: Exception) {} } }

    // ---------- Star Sports (jiotvpllive.cdn.jio.com) ----------
    // That CDN now refuses (451) the site-wide "acl=/*" token the TG TV site hands out. What plays is what the site's
    // other server (jjtvxweb.pages.dev/pind) does: a per-channel token from its Star cookie list, every ClearKey key
    // from its channel list, JioTV Referer + plaYtv UA, token as the whole query of manifest and segments.
    // Both addresses are re-read from that player page, so if the site moves them the app follows without an update.
    @Volatile private var starCookieUrl = "https://allinonereborn2.online/jtv-fetch/jstarcookie/cookie.json"
    @Volatile private var altChannelsUrl = "$ALT_SITE/jstr4web.json"
    @Volatile private var lastAltCfg = 0L
    @Volatile private var starCookies: Pair<Long, Map<String, String>> = 0L to emptyMap()
    @Volatile private var altChannels: Pair<Long, Map<String, JSONObject>> = 0L to emptyMap()

    fun isJioPl(u: String) = try { URL(u).host.equals("jiotvpllive.cdn.jio.com", true) } catch (_: Exception) { false }

    private fun altConfig() {
        val now = System.currentTimeMillis(); if (now - lastAltCfg < 30 * 60_000L) return; lastAltCfg = now
        val h = get("$ALT_SITE/pind?id=1106") ?: return
        Regex("COOKIE_STAR_SPORTS\\s*=\\s*[\"']([^\"']+)").find(h)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }?.let { starCookieUrl = it }
        Regex("COOKIE_PRIMARY\\s*=\\s*[\"']([^\"']+)").find(h)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }?.let { primaryCookieUrl = it }
        Regex("CHANNELS_URL\\s*=\\s*[\"']([^\"']+)").find(h)?.groupValues?.get(1)?.let { c ->
            altChannelsUrl = if (c.startsWith("http")) c else ALT_SITE + (if (c.startsWith("/")) c else "/$c") }
    }

    /** channel id -> "__hdnea__=..." made for that channel's path (good about an hour; re-read every minute). */
    private fun starCookie(id: String): String? {
        val now = System.currentTimeMillis()
        if (now - starCookies.first > 60_000L || starCookies.second.isEmpty()) {
            try { altConfig() } catch (_: Exception) {}
            val body = get(starCookieUrl, timeout = 8000)
            if (body != null) try {
                val o = JSONObject(body); val m = HashMap<String, String>()
                // same as the web player: successful results first, and the FIRST entry for a channel wins
                for (name in listOf("successful_results", "failed_results")) o.optJSONArray(name)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { r ->
                    val u = r.optJSONObject("error_details")?.optString("final_url", "").orEmpty().ifEmpty { r.optString("final_url", "") }
                    Regex("__hdnea__=([^&\"]+)").find(u)?.let { mm -> m.putIfAbsent(r.optString("channel_id"), "__hdnea__=" + mm.groupValues[1]) } } }
                if (m.isNotEmpty()) starCookies = now to m
            } catch (_: Exception) {}
        }
        return starCookies.second[id]
    }

    private fun altChannel(id: String): JSONObject? {
        val now = System.currentTimeMillis()
        if (now - altChannels.first > 10 * 60_000L || altChannels.second.isEmpty()) {
            try { altConfig() } catch (_: Exception) {}
            get(altChannelsUrl, timeout = 12000)?.let { b -> try {
                val a = JSONArray(b); val m = HashMap<String, JSONObject>()
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { m[it.optString("id")] = it }
                if (m.isNotEmpty()) altChannels = now to m
            } catch (_: Exception) {} }
        }
        return altChannels.second[id]
    }

    private fun mergeKeys(a: JSONObject?, b: JSONObject?): JSONObject? {
        val keys = JSONArray(); val seen = HashSet<String>()
        listOf(a, b).forEach { d -> d?.optJSONArray("keys")?.let { k -> for (i in 0 until k.length()) k.optJSONObject(i)?.let { x ->
            if (seen.add(x.optString("keyId"))) keys.put(x) } } }
        return if (keys.length() == 0) null else JSONObject().put("type", "clearkey").put("keys", keys)
    }

    /** Puts the per-channel token and every key on the channel's jiotvpllive streams; adds that stream first if missing. */
    private fun withStar(id: String, site: List<Server>): List<Server> {
        val alt = try { altChannel(id) } catch (_: Exception) { null }
        val altUrl = alt?.let { urlsOf(it).firstOrNull() }
        val starUrl = altUrl?.takeIf { isJioPl(it) } ?: site.firstOrNull { isJioPl(it.url) }?.url
        if (starUrl == null) return site
        val cookie = try { starCookie(id) } catch (_: Exception) { null } ?: return site
        val drm = mergeKeys(alt?.let { drmOf(it) }, site.firstOrNull { it.hasKeys }?.drm)
        val out = ArrayList<Server>()
        out.add(Server("Server 1", withCookie(starUrl, cookie), drm))
        site.forEach { s ->
            val u = if (isJioPl(s.url)) withCookie(s.url, cookie) else s.url
            if (out.none { it.url == u }) out.add(Server(s.label, u, mergeKeys(drm, s.drm)))
        }
        return out
    }

    fun fetch(id: String, fresh: Boolean = false): List<Server> {
        val now = System.currentTimeMillis()
        if (!fresh) cache[id]?.let { if (now - it.first < CACHE_MS) return it.second }
        val l = withStar(id, fetchSite(id, now))
        if (l.isNotEmpty()) cache[id] = now to l
        return l
    }

    private fun fetchSite(id: String, now: Long): List<Server> {
        var l = tryFn(fn, id)
        if (l.isEmpty() && now - lastDiscovery > 30_000L) {
            lastDiscovery = now
            val cands = discover(id).filter { it != fn }
            val tasks = cands.map { c -> Callable { c to tryFn(c, id) } }
            val futures = pool.invokeAll(tasks, 10, TimeUnit.SECONDS)
            for (f in futures) {
                val r = try { f.get() } catch (_: Exception) { null } ?: continue
                if (r.second.isNotEmpty()) { fn = r.first; prefs?.edit()?.putString("fn", fn)?.apply(); l = r.second; break }
            }
        }
        return l
    }

    private val LINK = Regex("https?://[^\\s\"'<>\\\\]+?\\.(?:m3u8|mpd)(?:\\?[^\\s\"'<>\\\\]*)?")
    private fun clean(h: String) = h.replace("\\u0026", "&").replace("\\u002F", "/").replace("\\/", "/").replace("&amp;", "&")

    /** Catches stream links off the website's own player page (when its server list is unavailable). */
    fun scrape(id: String): List<Server> {
        val out = ArrayList<Server>()
        for (page in listOf("$SITE/embed/$id", "$SITE/watch/$id")) {
            val h = get(page, "$SITE/") ?: continue
            LINK.findAll(clean(h)).forEach { m -> if (out.none { it.url == m.value }) out.add(Server("Page", m.value, null)) }
            if (out.isNotEmpty()) break
        }
        return out
    }

    // ---------- jjtvxweb.pages.dev, played natively exactly like its /pind player ----------
    @Volatile private var primaryCookieUrl = "https://allinonereborn2.online/jstrweb2/cookies.json"
    @Volatile private var primaryCookie: Pair<Long, String> = 0L to ""

    /** The site-wide "__hdnea__=..." token the /pind player puts on every non-jiotvpllive stream (re-read every minute). */
    private fun primaryCookie(): String {
        val now = System.currentTimeMillis()
        if (now - primaryCookie.first < 60_000L && primaryCookie.second.isNotEmpty()) return primaryCookie.second
        try { altConfig() } catch (_: Exception) {}
        val body = get(primaryCookieUrl, timeout = 8000) ?: return primaryCookie.second
        try {
            val a = JSONArray(body)
            for (i in 0 until a.length()) { val c = a.optJSONObject(i)?.optString("cookie", "").orEmpty()
                if (c.contains("__hdnea__=")) { primaryCookie = now to c.trim(); break } }
        } catch (_: Exception) {
            Regex("__hdnea__=[^\"&\\s]+").find(body)?.let { primaryCookie = now to it.value }
        }
        return primaryCookie.second
    }

    /** The backup site's channel entry for this channel: same name first, then the same id. */
    private fun altEntry(id: String, name: String): JSONObject? {
        altChannel(id)   // loads / refreshes the list
        val all = altChannels.second
        val key = Playlist.norm(name)
        prefs?.getString("alt_$key", null)?.let { all[it]?.let { e -> return e } }
        if (key.length >= 3) {
            all.values.firstOrNull { Playlist.norm(it.optString("name")) == key }?.let { return it }
            all.values.firstOrNull { val n = Playlist.norm(it.optString("name")); n.length >= 3 && (n.startsWith(key) || key.startsWith(n)) }?.let { return it }
        }
        return all[id]
    }

    /**
     * Exactly what jjtvxweb.pages.dev/pind?id=.. does, but in the native player: the channel's url, url1, url2, url3 from
     * its channel list, every ClearKey key, the per-channel Star token for jiotvpllive streams and the site-wide token
     * for all others, token as the whole query (Streams profile "jioweb" carries it to every segment with plaYtv UA +
     * JioTV Referer, same as the site's request filter).
     */
    fun scrapeAlt(name: String, id: String = ""): List<Server> {
        val e = altEntry(id, name) ?: return emptyList()
        val eid = e.optString("id")
        prefs?.edit()?.putString("alt_${Playlist.norm(name)}", eid)?.apply()
        val drm = drmOf(e)
        val urls = listOf("url", "url1", "url2", "url3").map { e.optString(it, "") }
            .filter { it.startsWith("http") }.map { it.replace("bpk-tv//", "bpk-tv/") }
        val out = ArrayList<Server>()
        for (u in urls) {
            val cookie = if (isJioPl(u)) (try { starCookie(eid) } catch (_: Exception) { null } ?: continue)
                         else try { primaryCookie() } catch (_: Exception) { "" }
            val full = if (cookie.isNotEmpty()) withCookie(u, cookie) else u
            if (out.none { it.url == full }) out.add(Server("Backup ${out.size + 1}", full, drm))
        }
        return out
    }

    /**
     * The backup player's address for a channel: https://jjtvxweb.pages.dev/pind?id=<id>. The id is looked up by channel name
     * in that site's own page and scripts (remembered once found); if that finds nothing, the channel's own id is used when
     * it is a number. Blocking: call off the main thread.
     */
    fun altUrl(chId: String, name: String): String {
        val key = Playlist.norm(name)
        prefs?.getString("alt_$key", null)?.let { return "$ALT_SITE/pind?id=$it" }
        var id: String? = null
        val html = get(ALT_SITE)
        if (html != null && key.length >= 3) {
            val texts = arrayListOf(clean(html))
            Regex("src=\"([^\"]+\\.js)\"").findAll(html).map { it.groupValues[1] }.distinct().take(6).map { s ->
                val u = if (s.startsWith("http")) s else ALT_SITE + (if (s.startsWith("/")) s else "/$s")
                pool.submit(Callable { get(u) })
            }.toList().forEach { f -> try { f.get(8, TimeUnit.SECONDS)?.let { texts.add(clean(it)) } } catch (_: Exception) {} }
            val idRx = Regex("(?:\\bid|channel_?id|channelId|\\bcid)[\"']?\\s*[:=]\\s*[\"']?(\\d{1,6})", RegexOption.IGNORE_CASE)
            val nameRx = Regex(Regex.escape(name.trim()) + "[\"']", RegexOption.IGNORE_CASE)
            outer@ for (t in texts) for (m in nameRx.findAll(t)) {
                val w = t.substring((m.range.first - 160).coerceAtLeast(0), (m.range.last + 160).coerceAtMost(t.length))
                val hit = idRx.find(w)
                if (hit != null) { id = hit.groupValues[1]; break@outer }
            }
        }
        if (id == null && chId.all { it.isDigit() } && chId.isNotEmpty()) id = chId
        if (id == null) return ALT_SITE
        prefs?.edit()?.putString("alt_$key", id)?.apply()
        return "$ALT_SITE/pind?id=$id"
    }
}
