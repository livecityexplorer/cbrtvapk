package app.iptv.feature.livetv

import app.iptv.core.domain.model.Playlist
import app.iptv.core.domain.model.PlaylistType
import org.junit.Assert.*
import org.junit.Test

class LiveSelectionTest {
    @Test fun roundTrips() {
        listOf(LiveSelection.All, LiveSelection.Favorites, LiveSelection.Recent, LiveSelection.Category(42)).forEach {
            assertEquals(it, LiveSelection.decode(it.encode()))
        }
    }

    @Test fun garbageFallsBackToAll() {
        assertEquals(LiveSelection.All, LiveSelection.decode(null))
        assertEquals(LiveSelection.All, LiveSelection.decode(""))
        assertEquals(LiveSelection.All, LiveSelection.decode("cat:abc"))
        assertEquals(LiveSelection.All, LiveSelection.decode("whatever"))
    }

    private fun pl(id: Long) = Playlist(id = id, name = "p$id", type = PlaylistType.M3U, url = "http://x")

    @Test fun activePlaylistResolution() {
        val list = listOf(pl(1), pl(2), pl(3))
        assertEquals(2L, resolveActivePlaylist(list, 2)?.id)
        assertEquals(1L, resolveActivePlaylist(list, 99)?.id) // stored one was deleted
        assertEquals(1L, resolveActivePlaylist(list, null)?.id)
        assertNull(resolveActivePlaylist(emptyList(), 1))
    }
}
