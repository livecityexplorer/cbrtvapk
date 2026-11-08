package app.iptv.feature.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import app.iptv.core.domain.model.*
import app.iptv.core.domain.repository.*
import app.iptv.core.domain.util.ActivePlaylist
import app.iptv.core.domain.util.ResumePolicy
import app.iptv.core.ui.BrowseCategory
import app.iptv.core.ui.BrowseState
import app.iptv.core.ui.PosterItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

internal fun Movie.toPoster() = PosterItem(
    id = id, title = name, imageUrl = posterUrl,
    subtitle = listOfNotNull(year, rating?.let { "★ " + "%.1f".format(it) }).joinToString(" · ").ifEmpty { null }
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
    private val vod: VodRepository,
    userData: UserDataRepository,
    prefs: PreferencesRepository
) : ViewModel() {

    private val playlists: StateFlow<List<Playlist>?> =
        playlistRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val active: StateFlow<Playlist?> =
        combine(playlists, prefs.activePlaylistId) { list, stored -> list?.let { ActivePlaylist.resolve(it, stored) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val categoryKey = handle.getStateFlow(CAT, -1L)
    val query: StateFlow<String> = handle.getStateFlow(QUERY, "")

    private val categories: Flow<List<BrowseCategory>> =
        active.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(emptyList<BrowseCategory>())
            else channelRepository.observeCategories(id, CategoryKind.MOVIE)
                .map { l -> l.filterNot { it.isHidden }.map { BrowseCategory(it.id, it.name) } }
        }

    val state: StateFlow<BrowseState> = combine(playlists, active, categories, categoryKey) { all, a, cats, cat ->
        BrowseState(loading = all == null, hasPlaylist = a != null, categories = cats, categoryId = cat.takeIf { it >= 0 })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowseState())

    val movies: Flow<PagingData<PosterItem>> = combine(
        active.map { it?.id }.distinctUntilChanged(), categoryKey, query.debounce { if (it.isEmpty()) 0L else 300L }
    ) { id, cat, q -> Triple(id, cat, q.trim()) }
        .flatMapLatest { (id, cat, q) ->
            if (id == null) flowOf(PagingData.empty<PosterItem>())
            else vod.movies(id, cat.takeIf { it >= 0 }, q).map { page -> page.map { it.toPoster() } }
        }.cachedIn(viewModelScope)

    val continueWatching: StateFlow<List<PosterItem>> =
        combine(userData.observeContinueWatching(50), active) { list, a ->
            list.filter { it.type == ContentType.MOVIE && it.playlistId == a?.id }.mapNotNull { h ->
                h.contentKey.toLongOrNull()?.let { PosterItem(it, h.title, h.imageUrl, null, ResumePolicy.progress(h.positionMs, h.durationMs)) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // A category can disappear after a refresh or when the playlist changes.
        viewModelScope.launch {
            categories.collect { cats ->
                val sel = categoryKey.value
                if (sel >= 0 && cats.isNotEmpty() && cats.none { it.id == sel }) handle[CAT] = -1L
            }
        }
    }

    fun selectCategory(id: Long?) { handle[CAT] = id ?: -1L }
    fun setQuery(q: String) { handle[QUERY] = q }

    private companion object { const val CAT = "cat"; const val QUERY = "query" }
}
