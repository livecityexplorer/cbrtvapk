package app.iptv.player.navigation

sealed class Route(val path: String, val label: String) {
    data object Home : Route("home", "Home")
    data object Search : Route("search", "Search")
    data object Live : Route("live", "Live TV")
    data object Epg : Route("epg", "Guide")
    data object Movies : Route("movies", "Movies")
    data object Series : Route("series", "Series")
    data object MultiView : Route("multiview", "Multi-View")
    data object Recordings : Route("recordings", "Recordings")
    data object Settings : Route("settings", "Settings")

    companion object {
        val topLevel = listOf(Home, Search, Live, Epg, Movies, Series, MultiView, Recordings, Settings)
        const val PLAYLISTS = "settings/playlists"
        const val LOCKS = "settings/locks"
        const val PLAYLIST_EDIT = "playlist_edit/{id}"
        fun playlistEdit(id: Long) = "playlist_edit/$id"
        const val NEW_PLAYLIST_ID = -1L
        const val MULTIVIEW = "multiview?channelId={channelId}"
        const val MOVIE_DETAIL = "movie/{id}"
        const val SERIES_DETAIL = "series/{id}"
        const val VOD_PLAYER = "vodplayer/{kind}/{id}?resume={resume}"
        fun movieDetail(id: Long) = "movie/$id"
        fun seriesDetail(id: Long) = "series/$id"
        /** [kind] is "movie" or "episode". resume=false restarts from the beginning. */
        fun vodPlayer(kind: String, id: Long, resume: Boolean = true) = "vodplayer/$kind/$id?resume=$resume"
        fun multiView(channelId: Long? = null) = if (channelId == null) "multiview" else "multiview?channelId=$channelId"
        const val PLAYER = "player/{channelId}?programStart={programStart}&programEnd={programEnd}"
        /** [start]/[end] set = catch-up of that programme. */
        fun player(channelId: Long, start: Long? = null, end: Long? = null) =
            "player/$channelId?programStart=${start ?: -1L}&programEnd=${end ?: -1L}"
    }
}
