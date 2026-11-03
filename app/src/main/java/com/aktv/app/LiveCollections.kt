package com.aktv.app

/**
 * Curated Live TV shelves.
 *
 * `allinonereborn2.online/sony/` lists its channels by opaque slug and embeds a third-party web
 * player (`suniliv.pages.dev/ptest1.html?id=<slug>`) that resolves the source with Media Source
 * Extensions - there is no static manifest to read, and the app deliberately never opens a web
 * player. Each slug does, however, name a Sony channel that is already in the native catalogue.
 *
 * The shelf is therefore selected from the **loaded catalogue by channel name**, never by a list of
 * hand-written IDs. An earlier revision hard-coded guessed Jio IDs and 14 of them resolved to
 * unrelated channels (Star Sports, Star Gold, Maa TV, a news channel); a live check caught it. Names
 * come from the provider, so this cannot drift into showing a different broadcaster.
 *
 * Playback still runs on the existing native TG/GioTV ClearKey path: nothing here remaps a source,
 * invents a URL, or borrows another channel's credentials.
 */
internal object LiveCollections {

    data class Spec(
        val title: String,
        val kicker: String,
        /** Name tokens in the order the source page presents them; longest match wins. */
        val order: List<String>,
        /** Builder-defined selection. Match the provider's own name, never a guessed ID. */
        val matches: (String) -> Boolean
    )

    /** Sony-branded channels plus the Sony-operated entries that do not carry the word Sony. */
    private fun isSony(name: String): Boolean {
        val n = name.lowercase()
        if (n.contains("liv sports")) return false          // SonyLIV app-only streams, not on the page
        return n.contains("sony") || n.startsWith("set ") || n == "set hd" ||
            n.contains("sony aath") || n.startsWith("aath") || n.startsWith("bbc earth")
    }

    /**
     * The Sony page's tiles in the order they appear on it, each named by the token that identifies it
     * in the provider's own channel names. Names are matched loosely (case/punctuation-insensitive)
     * because the provider writes them its way, e.g. `Sony Yay Hindi` for the page's SONY YAY. Plain and
     * HD tiles both exist upstream, so the tokens are precise enough to tell `Sony Max` from `Sony MAX2`
     * and `Sony Ten 3` from `Sony Ten 3 Hindi HD`.
     */
    private val PAGE_ORDER = listOf(
        "set hd",                                          // sony-hd (Sony's own identity for it)
        "sony sab hd", "sony sab sd",                      // sab-hd
        "sony marathi", "sony pal", "sony aath", "sony yay",// sony-marathi / pal / aath / yay
        "sony max hd", "sony max", "sony wah", "sony max2", "sony pix",   // sony-max-hd .. sony-pix-hd
        "sony ten 1 hd", "sony ten 1", "sony ten 2 hd", "sony ten 2",
        "sony ten 3 hd", "sony ten 3", "sony ten 4 hd", "sony ten 4",
        "sony ten 5 hd", "sony ten 5",
        "bbc earth"
    )

    val SONY = Spec(
        title = "Sony Live",
        kicker = "SONY CHANNELS \u00b7 NATIVE CLEARKEY",
        order = PAGE_ORDER,
        matches = ::isSony
    )

    val shelves: List<Spec> get() = listOf(SONY)

    /**
     * Position in the page order. The *longest* matching token wins, so a specific tile never inherits
     * the position of a shorter one it happens to contain - `Sony MAX2` is not `Sony Max`, and
     * `Sony Ten 3 Hindi HD` lands next to the other Ten 3 tiles instead of at the end.
     */
    private fun rank(name: String, spec: Spec): Int {
        val n = name.lowercase()
        return spec.order.withIndex()
            .filter { n.contains(it.value) }
            .maxByOrNull { it.value.length }?.index
            ?: spec.order.size
    }

    /**
     * Select the shelf from the catalogue. V2 IDs are excluded: they reuse numeric IDs for different
     * channels and are not Jio channel IDs. Order follows the source page, then by name.
     */
    fun channels(spec: Spec, catalogue: List<MainActivity.Channel>): List<MainActivity.Channel> =
        catalogue.filter { !it.v2 && spec.matches(it.name) }
            .distinctBy { it.key }
            .sortedWith(compareBy({ rank(it.name, spec) }, { it.name.lowercase() }))
}
