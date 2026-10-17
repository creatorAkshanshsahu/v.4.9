package com.aktv.app

/** The subset of browser Fetch behavior needed by the observed no-referrer JJTV metadata requests. */
internal class MetadataFetch(val url: String, val headers: Map<String, String>, val documentOrigin: String, val cors: Boolean)

internal object JjtvMetadataRequests {
    fun request(url: String, site: String, stage: JjtvLookupStage): MetadataFetch {
        val cors = stage !in setOf(JjtvLookupStage.PAGE, JjtvLookupStage.SCRIPT)
        val headers = linkedMapOf(
            "Accept" to if (stage == JjtvLookupStage.PAGE) "text/html,application/xhtml+xml" else "*/*",
            "Cache-Control" to "no-cache, no-store", "Pragma" to "no-cache"
        )
        FetchOrigin(site, url, cors).visit(url)?.let { headers["Origin"] = it }
        // Canonical/supplied URL, no invented cache-busting query and no Referer (even an empty one).
        return MetadataFetch(url, headers, site, cors)
    }
}

/** Per-request URL-list state, not per connection/channel. A same-origin first hop to another
 * origin is not yet tainted. A later cross-origin redirect from a foreign origin is opaque/null.
 */
internal class FetchOrigin(documentOrigin: String, initialUrl: String, private val cors: Boolean = true) {
    private val document = StreamRequestPolicy.originOf(documentOrigin)
    private var previous = StreamRequestPolicy.originOf(initialUrl)
    private var tainted = false
    fun visit(url: String): String? {
        val destination = StreamRequestPolicy.originOf(url)
        if (previous != document && destination != previous) tainted = true
        previous = destination
        return when {
            !cors || document.isBlank() -> null
            tainted -> "null"
            destination == document -> null
            else -> document
        }
    }
}
