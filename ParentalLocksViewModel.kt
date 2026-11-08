package app.iptv.feature.settings.parental

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.Category
import app.iptv.core.domain.model.CategoryKind
import app.iptv.core.domain.repository.ChannelRepository
import app.iptv.core.domain.repository.PlaylistRepository
import app.iptv.core.domain.repository.PreferencesRepository
import app.iptv.core.domain.util.ActivePlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which categories of the active playlist are PIN-locked (live, movies and series). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ParentalLocksViewModel @Inject constructor(
    playlists: PlaylistRepository,
    prefs: PreferencesRepository,
    private val channels: ChannelRepository
) : ViewModel() {

    val kind = MutableStateFlow(CategoryKind.LIVE)

    /** null = still loading; empty = no playlist or no categories. */
    val categories: StateFlow<List<Category>?> =
        combine(playlists.observeAll(), prefs.activePlaylistId, kind) { all, stored, k -> ActivePlaylist.resolve(all, stored)?.id to k }
            .distinctUntilChanged()
            .flatMapLatest { (id, k) -> if (id == null) flowOf(emptyList<Category>()) else channels.observeCategories(id, k) }
            .map<List<Category>, List<Category>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(k: CategoryKind) { kind.value = k }
    fun toggle(c: Category) { viewModelScope.launch { channels.setCategoryLocked(c.id, !c.isLocked) } }
}
