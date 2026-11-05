package com.aktv.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Whether this device may run a page in the browser engine at all.
 *
 * The site-player search (OG's servers, TG FROST's `/play` page, the JioTV gate) needs a browser engine. On a
 * television box that engine is often a stripped Chromium that takes the whole process down when it is
 * created, which is why "it crashes on the TV" and "it works on the phone" were both true. So the engine is
 * off by default on a TV or a low-RAM device, the native paths never touch it there, and the viewer can turn
 * it back on deliberately from the player's source menu once the box is known to survive it.
 */
internal object SiteBrowserGate {

    @Volatile var allowed: Boolean = true
    @Volatile var reason: String = ""

    fun isTelevision(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature("android.hardware.type.television")) return true
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun isLowRam(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    /** The default for a device that has never been asked: phones and tablets yes, TVs no. */
    fun defaultFor(context: Context): Boolean = !isTelevision(context) && !isLowRam(context)

    /** Reads the stored choice (or the device default) and records why the engine is off, for the report. */
    fun load(context: Context, stored: Boolean?, explain: Boolean = true) {
        allowed = stored ?: defaultFor(context)
        reason = when {
            allowed -> ""
            explain.not() -> ""
            isTelevision(context) -> "off on this TV (its browser engine crashes the app)"
            isLowRam(context) -> "off on this low-memory device"
            else -> ""
        }
    }

    fun summary(): String = when {
        allowed -> "site player search: on"
        reason.isNotBlank() -> "site player search: $reason"
        else -> "site player search: off"
    }
}
