package com.aktv.app

/**
 * Curated Live TV shelves.
 *
 * `allinonereborn2.online/sony/` lists its channels by opaque slug and embeds a third-party web
 * player (`suniliv.pages.dev/ptest1.html?id=<slug>`) that resolves the source with Media Source
 * Extensions — there is no static manifest to read, and the app deliberately never opens a web
 * player. Each slug does, however, name a Sony channel that is already in the native catalogue,
 * under the same Jio channel ID everything else uses.
 *
 * So the shelf is defined as the exact native channel IDs, ordered the way the Sony page presents
 * them. Playback stays on the existing native TG/GioTV path with ClearKey DRM: nothing here
 * remaps a channel, invents a URL, or borrows another channel's credentials.
 *
 * Rules held by `LiveCollectionsTest`:
 * - IDs are exact and unique within a shelf; no fuzzy or name-based matching.
 * - An entry that is not in the loaded catalogue is dropped, never substituted with a lookalike.
 * - Order is editorial, so the shelf reads like the source page instead of alphabetical noise.
 */
internal object LiveCollections {

    data class Spec(val title: String, val kicker: String, val ids: List<String>)

    /**
     * Sony channels, matching the Sony page's own order. SONY AATH is intentionally absent from the
     * preferred list until it appears in a provider catalogue; adding an ID that no provider
     * advertises would only produce a dead tile.
     */
    val SONY = Spec(
        title = "Sony Live",
        kicker = "SONY CHANNELS · NATIVE CLEARKEY",
        ids = listOf(
            "1103", // SONY HD
            "1114", // SAB HD
            "1122", // SONY MARATHI
            "1123", // SONY PAL
            "1124", // SONY MAX HD
            "1125", // SONY MAX
            "1126", // SONY WAH
            "1127", // SONY MAX 2
            "3284", // SONY PIX HD
            "1106", // SONY TEN 1 HD
            "1107", // SONY TEN 1
            "1108", // SONY TEN 1 HINDI HD
            "1109", // SONY TEN 2 HD
            "1110", // SONY TEN 2
            "1111", // SONY TEN 3 HD
            "524",  // SONY TEN 3
            "1112", // SONY TEN 4 HD
            "1113", // SONY TEN 4
            "1118", // SONY TEN 5 HD
            "1119", // SONY TEN 5
            "1142", // BBC EARTH
            "872",  // SONY YAY
        )
    )

    val shelves: List<Spec> get() = listOf(SONY)

    /**
     * Exact-ID lookup, preserving editorial order and dropping anything not advertised.
     * Only V1/Jio IDs are valid here: a V2 catalogue reuses numeric IDs for different channels.
     */
    fun channels(spec: Spec, catalogue: List<MainActivity.Channel>): List<MainActivity.Channel> {
        val byId = catalogue.filterNot { it.v2 }.associateBy { it.id }
        return spec.ids.mapNotNull { byId[it] }.distinctBy { it.key }
    }
}
