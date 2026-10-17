package com.aktv.app

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android lifetime/persistence adapter. Only a verified function id is persisted, never tokens or DRM. */
object TgServers {
    const val SITE = TgResolver.SITE
    @Volatile private var resolver = TgResolver()
    private val refreshExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "tg-site-refresh").apply { isDaemon = true } }
    private val refreshing = AtomicBoolean(false)
    @Volatile private var refreshChannel: String? = null

    fun init(ctx: Context) {
        val prefs = ctx.getSharedPreferences("aktv_tg", Context.MODE_PRIVATE)
        if (prefs.getInt("native_schema", 0) != 2) prefs.edit().clear().putInt("native_schema", 2).apply()
        resolver.close()
        resolver = TgResolver(initialFunction = prefs.getString("native_function", null), saveFunction = {
            prefs.edit().putString("native_function", it).apply()
        })
    }

    fun fetch(id: String, fresh: Boolean = false, v2: Boolean = false): List<TgStream> {
        if (!v2) refreshChannel = id
        return resolver.resolve(id, v2, fresh)
    }

    fun invalidate(id: String, v2: Boolean = false) = resolver.invalidate(id, v2)

    /** Warm current player chunks without resolving every channel, and check them again while the app is open. */
    fun refreshSite(id: String? = refreshChannel) {
        if (id == null || !refreshing.compareAndSet(false, true)) return
        refreshChannel = id
        refreshExecutor.execute {
            try { resolver.discover(id) } catch (_: Exception) { } finally { refreshing.set(false) }
        }
    }
}
