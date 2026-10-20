package com.aktv.app

import java.net.URI

/** Discover the currently deployed player chunks, including lazy imports and Vite's dependency map. */
object TgDiscovery {
    // Rolldown emits static backtick literals as well as single/double quotes. Do not execute JS.
    private val scripts = Regex("""(["'`])([^"'`<>\s]+\.(?:m?js)(?:\?[^"'`<>\s]*)?)\1""", RegexOption.IGNORE_CASE)
    private val tagged = Regex("""_serverFn/([A-Za-z0-9_-]{8,128})(?=[?"'`\s]|$)""")
    // Only function calls / id metadata, not every long hex string (DRM keys are not server functions).
    private val called = Regex("""[A-Za-z_$][A-Za-z0-9_$.]*\(\s*(["'`])([a-fA-F0-9]{64})\1""")
    private val metadata = Regex("""["']?\bid["']?\s*:\s*(["'`])([a-fA-F0-9]{64})\1""")

    fun scripts(text: String, from: String, site: String): List<String> {
        val source = text.replace("\\/", "/").replace("\\u002F", "/")
        val origin = StreamRequestPolicy.originOf(site)
        return scripts.findAll(source).mapNotNull { match ->
            try {
                val raw = match.groupValues[2].replace("&amp;", "&")
                if (raw.contains("\${")) return@mapNotNull null
                // Vite's dependency map uses root-relative asset names without a leading slash.
                val base = if (raw.startsWith("assets/")) "$site/" else from
                val uri = URI(base).resolve(raw)
                uri.toString().takeIf { uri.userInfo == null && StreamRequestPolicy.originOf(it) == origin }
            } catch (_: Exception) { null }
        }.distinct().sortedBy { priority(it) }.toList()
    }

    fun functions(script: String): List<String> = (
        tagged.findAll(script).map { it.groupValues[1] } +
            called.findAll(script).map { it.groupValues[2] } +
            metadata.findAll(script).map { it.groupValues[2] }
        ).distinct().take(12).toList()

    fun priority(url: String): Int = when {
        url.contains("servers", true) -> 0
        url.contains("player", true) -> 1
        url.contains("watch", true) || url.contains("embed", true) -> 2
        else -> 3
    }
}
