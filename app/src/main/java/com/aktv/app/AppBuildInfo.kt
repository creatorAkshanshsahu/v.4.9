package com.aktv.app

import android.content.Context

/** Identifies the actual installed artifact; a version name alone can describe several CI builds. */
internal object AppBuildInfo {
    fun label(context: Context): String {
        val info = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        val run = context.getString(R.string.build_run_id).takeIf { it.matches(Regex("[0-9]{1,20}|local")) } ?: "local"
        return "AK TV ${info?.versionName ?: "unknown"} / code ${info?.longVersionCode ?: 0} / build $run"
    }
}
