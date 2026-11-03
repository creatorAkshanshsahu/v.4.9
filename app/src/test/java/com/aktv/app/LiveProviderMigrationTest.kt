package com.aktv.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Moving the second live provider to a new site must not leave a stale, unreachable hint behind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveProviderMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun seedLegacyHints() {
        context.getSharedPreferences("aktv", Context.MODE_PRIVATE).edit()
            .putString("source_provider_872", "https://jjtvxweb.pages.dev")
            .putString("source_872", "old-fingerprint")
            .putBoolean("native_compat_872_JJTV", true)
            .putStringSet("favs", mutableSetOf("872", "165"))
            .putString("recent_ch", "872")
            .putBoolean("previews", false)
            .putBoolean("native_force_tv", true)
            .apply()
        context.getSharedPreferences("aktv_tg", Context.MODE_PRIVATE).edit()
            .putString("native_function", "verified-tg-function")
            .apply()
    }

    @Test fun `hints that name the previous site are dropped once`() {
        seedLegacyHints()
        TgServers.forgetPreviousLiveSite(context)
        val prefs = context.getSharedPreferences("aktv", Context.MODE_PRIVATE)
        assertNull(prefs.getString("source_provider_872", null))
        assertNull(prefs.getString("source_872", null))
        assertFalse(prefs.contains("native_compat_872_JJTV"))
        assertEquals(LiveProvider.JJTV.website, prefs.getString("native_provider_site", null))
    }

    @Test fun `unrelated preferences and the verified TG function survive`() {
        seedLegacyHints()
        TgServers.forgetPreviousLiveSite(context)
        val prefs = context.getSharedPreferences("aktv", Context.MODE_PRIVATE)
        assertEquals(setOf("872", "165"), prefs.getStringSet("favs", null))
        assertEquals("872", prefs.getString("recent_ch", null))
        assertFalse(prefs.getBoolean("previews", true))
        assertTrue(prefs.getBoolean("native_force_tv", false))
        assertEquals("verified-tg-function",
            context.getSharedPreferences("aktv_tg", Context.MODE_PRIVATE).getString("native_function", null))
    }

    @Test fun `a later success on the current site is not discarded on the next launch`() {
        TgServers.forgetPreviousLiveSite(context)
        val prefs = context.getSharedPreferences("aktv", Context.MODE_PRIVATE)
        prefs.edit().putString("source_provider_872", LiveProvider.JJTV.website)
            .putString("source_872", "current-fingerprint").apply()
        TgServers.forgetPreviousLiveSite(context)
        assertEquals(LiveProvider.JJTV.website, prefs.getString("source_provider_872", null))
        assertEquals("current-fingerprint", prefs.getString("source_872", null))
    }
}
