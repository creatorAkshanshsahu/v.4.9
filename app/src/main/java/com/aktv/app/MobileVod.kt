package com.aktv.app

/**
 * The mobile edition's VOD lookup, shared by its home and its player overlay: the TV shelf's
 * provider chain (FROST, OUT, OG in order, each retried fresh once when it errors instead of
 * answering) in one call, plus the episode-list encoding the player uses for in-player switching.
 * Lives in the shared set so both mobile entry points — and the unit tests — resolve identically.
 */
internal object MobileVod {
    data class Episode(val season: Int, val episode: Int, val name: String)

    fun resolve(type: String, tmdb: Int, season: Int, episode: Int): Pair<List<FrostSource>, String?> {
        val frostTitle = try {
            FrostTitle(tmdb, type, season, episode)
        } catch (_: IllegalArgumentException) {
            return emptyList<FrostSource>() to "pick an episode first"
        }
        val resolver = FrostResolver()
        var reason: String? = null
        for (provider in listOf(FrostProvider.FROST, FrostProvider.OUT, FrostProvider.OG)) {
            val attempt = runCatching { resolver.resolve(frostTitle, provider) }
            val sources = attempt.getOrNull().orEmpty()
            if (sources.isNotEmpty()) return sources to null
            val fresh = attempt.exceptionOrNull()?.let {
                if (reason == null) reason = MobileErrors.why(it)
                runCatching { resolver.resolve(frostTitle, provider, fresh = true) }.getOrNull().orEmpty()
            }.orEmpty()
            if (fresh.isNotEmpty()) return fresh to null
        }
        return emptyList<FrostSource>() to reason
    }

    fun encodeEpisodes(episodes: List<Episode>): String =
        episodes.take(400).joinToString("\n") { "${it.season}\t${it.episode}\t${it.name.take(120)}" }

    fun decodeEpisodes(raw: String): List<Episode> = raw.lines().mapNotNull { line ->
        val parts = line.split("\t")
        val season = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val episode = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        if (season <= 0 || episode <= 0) null else Episode(season, episode, parts.getOrNull(2).orEmpty())
    }.take(400)
}
