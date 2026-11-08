package app.iptv.feature.livetv

import app.iptv.core.domain.model.Playlist

/** What the channel list is showing. Stored in SavedStateHandle as a string. */
sealed interface LiveSelection {
    data object All : LiveSelection
    data object Favorites : LiveSelection
    data object Recent : LiveSelection
    data class Category(val id: Long) : LiveSelection

    fun encode(): String = when (this) {
        All -> "all"
        Favorites -> "fav"
        Recent -> "recent"
        is Category -> "cat:$id"
    }

    companion object {
        fun decode(s: String?): LiveSelection = when {
            s == "fav" -> Favorites
            s == "recent" -> Recent
            s != null && s.startsWith("cat:") -> s.removePrefix("cat:").toLongOrNull()?.let { Category(it) } ?: All
            else -> All
        }
    }
}

/** The stored playlist if it still exists, otherwise the first one. */
internal fun resolveActivePlaylist(playlists: List<Playlist>, storedId: Long?): Playlist? =
    playlists.firstOrNull { it.id == storedId } ?: playlists.firstOrNull()
