package app.iptv.player.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.*
import app.iptv.core.domain.repository.*
import app.iptv.core.domain.util.ActivePlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class HomeUi(
    val loading: Boolean = true,
    val hasPlaylist: Boolean = false,
    val continueWatching: List<WatchHistory> = emptyList(),
    val favorites: List<Channel> = emptyList(),
    val recentMovies: List<Movie> = emptyList(),
    val recentSeries: List<Series> = emptyList()
) {
    val isEmpty get() = continueWatching.isEmpty() && favorites.isEmpty() && recentMovies.isEmpty() && recentSeries.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    playlists: PlaylistRepository,
    prefs: PreferencesRepository,
    userData: UserDataRepository,
    channels: ChannelRepository,
    vod: VodRepository
) : ViewModel() {

    private val activeId: Flow<Long?> = combine(playlists.observeAll(), prefs.activePlaylistId) { all, stored ->
        ActivePlaylist.resolve(all, stored)?.id
    }.distinctUntilChanged()

    private val vodRows: Flow<Pair<List<Movie>, List<Series>>> = activeId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<Movie>() to emptyList())
        else combine(vod.observeRecentMovies(id, ROW_LIMIT), vod.observeRecentSeries(id, ROW_LIMIT)) { m, s -> m to s }
    }

    val ui: StateFlow<HomeUi> = combine(
        activeId, userData.observeContinueWatching(ROW_LIMIT * 2), channels.observeFavorites(), vodRows
    ) { id, history, favorites, (movies, series) ->
        HomeUi(
            loading = false, hasPlaylist = id != null,
            continueWatching = history.filter { it.type == ContentType.MOVIE || it.type == ContentType.EPISODE }.take(ROW_LIMIT),
            favorites = favorites.filter { id == null || it.playlistId == id }.take(ROW_LIMIT * 2),
            recentMovies = movies, recentSeries = series
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    private companion object { const val ROW_LIMIT = 15 }
}
