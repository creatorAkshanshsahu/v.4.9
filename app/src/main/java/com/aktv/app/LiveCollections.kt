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
        /** Preferred channel names in the order the source page presents them. */
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
     * The Sony page's own channel order. Names are matched loosely (case/punctuation-insensitive)
     * because the provider writes them its own way, e.g. `Sony Yay Hindi` for the page's SONY YAY.
     */
    private val PAGE_ORDER = listOf(
        "set hd", "sony hd", "sab hd", "sony sab", "sony marathi", "sony pal", "sony aath",
        "sony yay", "sony max hd", "sony max2", "sony max", "sony wah", "sony pix",
        "sony ten 1", "sony ten 2", "sony ten 3", "sony ten 4", "sony ten 5",
        "bbc earth"
    )

    val SONY = Spec(
        title = "Sony Live",
        kicker = "SONY CHANNELS \u00b7 NATIVE CLEARKEY",
        order = PAGE_ORDER,
        matches = ::isSony
    )

    val shelves: List<Spec> get() = listOf(SONY)

    /** Rank of a channel name against the page order; unknown Sony entries sort last, alphabetically. */
    private fun rank(name: String, spec: Spec): Int {
        val n = name.lowercase()
        return spec.order.indexOfFirst { n.contains(it) }.let { if (it < 0) spec.order.size else it }
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
