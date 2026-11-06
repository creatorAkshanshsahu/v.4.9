package com.aktv.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * The browser engine is gone from this app.
 *
 * Every page-reading path (OG's servers, TG FROST's `/play` page, the JioTV gate, the trailer panel) used a
 * WebView, and on a television that engine is a stripped Chromium that takes the whole process down when it is
 * created - which is why the app opened and closed on a TV and ran on a phone. Nothing here creates one any
 * more: the flag is a constant, so the engine cannot be switched on by a stored preference either, and the
 * native paths (TG's own endpoint, the movie catalogue, the channel playlist) are the only ones left.
 */
internal object SiteBrowserGate {

    /** Always false. Kept as a name so the reports and the player menu can say so plainly. */
    const val allowed: Boolean = false

    val reason: String get() = "browser engine removed"

    fun isTelevision(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature("android.hardware.type.television")) return true
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun isLowRam(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    fun defaultFor(context: Context): Boolean = false

    /** No-op: there is no engine to configure. The stored value is ignored, not read. */
    fun load(context: Context, stored: Boolean?, explain: Boolean = true) = Unit

    fun summary(): String = "site player search: off · browser engine removed"
}
