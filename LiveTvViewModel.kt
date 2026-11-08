package app.iptv.feature.livetv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.iptv.core.domain.model.*
import app.iptv.core.domain.repository.*
import app.iptv.core.domain.util.EpgKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveUiState(
    val loading: Boolean = true,
    val playlists: List<Playlist> = emptyList(),
    val active: Playlist? = null,
    val categories: List<Category> = emptyList(),
    val selection: LiveSelection = LiveSelection.All,
    val query: String = "",
    val viewMode: LiveViewMode = LiveViewMode.LIST,
    val showHidden: Boolean = false
) {
    /** Reordering is only well-defined inside one category with no search filter. */
    val canReorder: Boolean get() = selection is LiveSelection.Category && query.isBlank()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
    private val userData: UserDataRepository,
    private val epgRepository: EpgRepository,
    private val prefs: PreferencesRepository,
    private val parental: ParentalGate
) : ViewModel() {

    private val selectionKey = handle.getStateFlow(SEL, LiveSelection.All.encode())
    val query: StateFlow<String> = handle.getStateFlow(QUERY, "")
    private val showHidden = MutableStateFlow(false)

    private val playlists: StateFlow<List<Playlist>?> =
        playlistRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val activePlaylist: StateFlow<Playlist?> =
        combine(playlists, prefs.activePlaylistId) { list, stored -> list?.let { resolveActivePlaylist(it, stored) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val categories: Flow<List<Category>> =
        activePlaylist.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(emptyList<Category>()) else channelRepository.observeCategories(id, CategoryKind.LIVE)
        }

    private data class Base(val playlists: List<Playlist>?, val active: Playlist?, val categories: List<Category>)

    val uiState: StateFlow<LiveUiState> = combine(
        combine(playlists, activePlaylist, categories) { p, a, c -> Base(p, a, c) },
        selectionKey, query, prefs.liveViewMode, showHidden
    ) { base, sel, q, mode, hidden ->
        LiveUiState(
            loading = base.playlists == null, playlists = base.playlists.orEmpty(), active = base.active,
            categories = base.categories, selection = LiveSelection.decode(sel), query = q, viewMode = mode, showHidden = hidden
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LiveUiState())

    private data class Params(val playlistId: Long?, val selection: LiveSelection, val query: String)

    // Clearing the search applies instantly; typing is debounced so every keystroke doesn't re-query.
    private val params: Flow<Params> = combine(
        activePlaylist.map { it?.id }.distinctUntilChanged(),
        selectionKey.map { LiveSelection.decode(it) },
        query.debounce { if (it.isEmpty()) 0L else 300L }
    ) { id, sel, q -> Params(id, sel, q.trim()) }

    /** Paged results for "All" and a single category. */
    val pagedChannels: Flow<PagingData<Channel>> = params.flatMapLatest { p ->
        val id = p.playlistId
        val sel = p.selection
        when {
            id == null -> flowOf(PagingData.empty<Channel>())
            sel is LiveSelection.All -> channelRepository.channels(id, null, p.query)
            sel is LiveSelection.Category -> channelRepository.channels(id, sel.id, p.query)
            else -> flowOf(PagingData.empty<Channel>())
        }
    }.cachedIn(viewModelScope)

    /** Favorites / Recently watched (small, unpaged). null while not applicable or loading. */
    val staticChannels: StateFlow<List<Channel>?> = params.flatMapLatest<Params, List<Channel>?> { p ->
        val id = p.playlistId
        when {
            id == null -> flowOf(null)
            p.selection is LiveSelection.Favorites -> channelRepository.observeFavorites().map { filterFor(it, id, p.query) }
            p.selection is LiveSelection.Recent -> channelRepository.observeRecent(RECENT_LIMIT).map { filterFor(it, id, p.query) }
            else -> flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val favoriteIds: StateFlow<Set<Long>> = userData.observeFavoriteIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        // A category can disappear after a refresh or when switching playlists.
        viewModelScope.launch {
            categories.collect { cats ->
                val sel = LiveSelection.decode(selectionKey.value)
                if (sel is LiveSelection.Category && cats.isNotEmpty() && cats.none { it.id == sel.id }) select(LiveSelection.All)
            }
        }
    }

    /** Current + next programme for a row. [nowMinute] changes once a minute so the query refreshes. */
    fun nowNext(channel: Channel, nowMinute: Long): Flow<List<EpgProgram>> =
        epgRepository.observeNowAndNext(EpgKey.of(channel.tvgId, channel.tvgName, channel.name), nowMinute)

    // ---- parental controls
    val hasPin: StateFlow<Boolean> = parental.hasPin.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    suspend fun needsUnlock() = parental.isLockedNow()
    suspend fun categoryNeedsPin(id: Long) = parental.categoryNeedsPin(id)
    suspend fun verifyPin(pin: String) = parental.verify(pin)
    fun toggleChannelLock(channel: Channel) { viewModelScope.launch { channelRepository.setChannelLocked(channel.id, !channel.isLocked) } }
    fun toggleCategoryLock(category: Category) { viewModelScope.launch { channelRepository.setCategoryLocked(category.id, !category.isLocked) } }

    fun select(selection: LiveSelection) { handle[SEL] = selection.encode() }
    fun setQuery(q: String) { handle[QUERY] = q }
    fun toggleShowHidden() { showHidden.update { !it } }

    fun toggleViewMode() {
        viewModelScope.launch {
            prefs.setLiveViewMode(if (uiState.value.viewMode == LiveViewMode.LIST) LiveViewMode.GRID else LiveViewMode.LIST)
        }
    }

    fun setActivePlaylist(id: Long) { viewModelScope.launch { prefs.setActivePlaylistId(id) } }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { userData.setFavorite(channel.id, channel.id !in favoriteIds.value) }
    }

    fun toggleCategoryHidden(category: Category) {
        viewModelScope.launch { channelRepository.setCategoryHidden(category.id, !category.isHidden) }
    }

    fun move(channel: Channel, up: Boolean) {
        viewModelScope.launch { channelRepository.moveChannel(channel.playlistId, channel.categoryId, channel.id, up) }
    }

    /** Records the channel in "Recently watched"; the caller navigates to the player. */
    fun onPlay(channel: Channel) {
        viewModelScope.launch {
            userData.saveProgress(
                WatchHistory(
                    type = ContentType.CHANNEL, contentKey = channel.id.toString(), playlistId = channel.playlistId,
                    title = channel.name, imageUrl = channel.logoUrl, streamUrl = channel.streamUrl,
                    positionMs = 0, durationMs = 0, updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun filterFor(list: List<Channel>, playlistId: Long, q: String) =
        list.filter { it.playlistId == playlistId && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }

    private companion object {
        const val SEL = "sel"
        const val QUERY = "query"
        const val RECENT_LIMIT = 50
    }
}
