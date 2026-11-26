package com.aktv.app

/** One-line, secret-free failure reasons for empty states. Never leaks URLs, keys or cookies. */
internal object MobileErrors {
    fun why(error: Throwable): String {
        val raw = (error.message.orEmpty().ifBlank { error.javaClass.simpleName })
            .replace(Regex("https?://[^\\s]+"), "·")
            .replace(Regex("[0-9a-fA-F]{16,}"), "·")
            .replace(Regex("\\s+"), " ").trim().take(140)
        return raw.ifBlank { "request failed" }
    }
}
