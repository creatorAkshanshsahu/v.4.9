package com.aktv.app

/**
 * Where the movies and series of theogpiratebot.online are played from.
 *
 * The site is a metadata front end: its catalogue is TMDB, and every title's Play button opens
 * `theogpiratebot.online/player?id=<tmdb>&type=movie|tv&server=<n>`. That page is the site's own player,
 * and the servers behind it are the third party providers it lists - they are embedded by that page and
 * chosen there, not by this app. Extracting their addresses would mean copying a provider's private
 * player and breaking the moment it rotates; opening the site's own player keeps working, and keeps the
 * servers the site's own, which is the same rule the Sony section follows.
 *
 * `server=0` is the number the site's own Play button uses, so the picker starts there.
 */
internal object OgSources {

    const val SITE = "https://theogpiratebot.online"
    /** The site's own Play link ships `server=0`; its player lists the rest of its servers itself. */
    const val SERVERS = 4

    /** "movie" or "tv", never the catalogue's `series` wording, and never an invented server number. */
    fun kind(type: String): String = if (type.equals("tv", true) || type.equals("series", true)) "tv" else "movie"

    fun label(server: Int): String = "Server ${server + 1}"

    /**
     * The site's player for a title on one of its servers. A series carries its season and episode, so
     * the same picker works for both sections of the catalogue.
     */
    fun playerUrl(type: String, tmdb: Int, server: Int, season: Int = 0, episode: Int = 0): String {
        require(tmdb > 0) { "Invalid TMDB identity" }
        val kind = kind(type)
        val index = server.coerceIn(0, SERVERS - 1)
        val base = "$SITE/player?id=$tmdb&type=$kind&server=$index"
        return if (kind == "tv" && season > 0 && episode > 0) "$base&season=$season&episode=$episode" else base
    }

    /** One line per server for the source menu, so the player names exactly what it will open. */
    fun menu(type: String, tmdb: Int, season: Int = 0, episode: Int = 0): List<Pair<String, String>> =
        (0 until SERVERS).map { server ->
            "OG player · ${label(server)} (site player)" to playerUrl(type, tmdb, server, season, episode)
        }
}
