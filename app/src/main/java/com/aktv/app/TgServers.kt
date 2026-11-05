package com.aktv.app

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android lifetime/persistence adapter. Only a verified function id is persisted, never tokens or DRM. */
object TgServers {
    const val SITE = TgResolver.SITE
    @Volatile private var resolver = TgResolver()
    @Volatile private var jjtv = JjtvResolver()
    @Volatile private var jioM3u = JioM3uResolver()
    private val refreshExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "tg-site-refresh").apply { isDaemon = true } }
    private val refreshing = AtomicBoolean(false)
    @Volatile private var refreshChannel: String? = null

    fun init(ctx: Context) {
        val prefs = ctx.getSharedPreferences("aktv_tg", Context.MODE_PRIVATE)
        if (prefs.getInt("native_schema", 0) != 2) prefs.edit().clear().putInt("native_schema", 2).apply()
        forgetPreviousLiveSite(ctx)
        resolver.close()
        jjtv = JjtvResolver()
        // The OG player's stream only exists after its scripts run, so the resolver needs the browser engine.
        OgNative.appContext = ctx.applicationContext
        jioM3u = JioM3uResolver(
            // The list can be a megabyte over a link that is barely moving: it gets its own timeouts.
            playlistHttp = TgHttp(readTimeoutSeconds = 30, callTimeoutSeconds = 45),
            extra = listOf(JioM3uProtocol.TATA_PLAYLIST),
            cacheDir = java.io.File(ctx.filesDir, "playlists"),
            legacyCacheFile = java.io.File(ctx.filesDir, "jiotv-playlist.m3u8"),
            // The site's front door refuses a scriptless client (HTTP 415 on every request shape), so the
            // last resort is the same request read through the browser engine.
            browser = { url -> BrowserFetch.playlist(ctx, url) }
        )
        resolver = TgResolver(initialFunction = prefs.getString("native_function", null), saveFunction = {
            prefs.edit().putString("native_function", it).apply()
        })
    }

    fun fetch(id: String, fresh: Boolean = false, v2: Boolean = false): List<TgStream> {
        if (!v2) refreshChannel = id
        return resolver.resolve(id, v2, fresh)
    }

    internal fun fetchFrom(provider: LiveProvider, id: String, fresh: Boolean = false, v2: Boolean = false): List<TgStream> = when (provider) {
        LiveProvider.TG -> fetch(id, fresh, v2)
        LiveProvider.JJTV -> {
            require(!v2) { "JJTV uses V1/Jio IDs, not V2 catalogue IDs" }
            jjtv.resolve(id, fresh)
        }
        LiveProvider.JIOM3U -> {
            require(!v2) { "JioTV m3u8 uses V1/Jio IDs, not V2 catalogue IDs" }
            jioM3u.resolve(id.removePrefix("jio:"), fresh)
        }
    }

    /** Returns all channel entries from the JioTV m3u8 playlist, for building the UI catalogue. */
    internal fun jioCatalogue(fresh: Boolean = false): List<JioM3uProtocol.Entry> = jioM3u.catalogue(fresh)

    /** The site's second list, on its own, so its section is built from it alone. */
    internal fun tataCatalogue(fresh: Boolean = false): List<JioM3uProtocol.Entry> =
        jioM3u.catalogueOf(JioM3uProtocol.TATA_PLAYLIST, fresh)

    fun invalidate(id: String, v2: Boolean = false) = resolver.invalidate(id, v2)

    /** Playlist channels share one cached playlist and key document; a refusal re-reads both. */
    fun invalidateJio() = jioM3u.invalidate()

    /**
     * A remembered provider/source hint names the website that supplied it. Once a build points at a
     * different live site, an old JJTV hint no longer describes anything the app can reach, and the
     * old fingerprint can never match a source from the new site. Drop only those stale hints: the
     * TG function id and every other preference stay intact, and no credential was ever stored here.
     */
    internal fun forgetPreviousLiveSite(ctx: Context) {
        val prefs = ctx.getSharedPreferences("aktv", Context.MODE_PRIVATE)
        if (prefs.getString("native_provider_site", null) == LiveProvider.JJTV.website) return
        val editor = prefs.edit().putString("native_provider_site", LiveProvider.JJTV.website)
        for (key in prefs.all.keys) {
            if (key.startsWith("source_provider_") || key.startsWith("source_") || key.startsWith("native_compat_")) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    /** Warm current player chunks without resolving every channel, and check them again while the app is open. */
    fun refreshSite(id: String? = refreshChannel) {
        if (id == null || !refreshing.compareAndSet(false, true)) return
        refreshChannel = id
        refreshExecutor.execute {
            try { resolver.discover(id) } catch (_: Exception) { } finally { refreshing.set(false) }
        }
    }
}
