package app.iptv.feature.settings.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.Playlist
import app.iptv.core.domain.repository.PlaylistRepository
import app.iptv.core.domain.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistListViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val sync: SyncManager
) : ViewModel() {
    /** null = still loading. */
    val playlists: StateFlow<List<Playlist>?> =
        repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val syncStates = sync.states

    fun refresh(id: Long) = sync.sync(id)

    fun delete(id: Long) {
        viewModelScope.launch {
            sync.cancel(id)
            repository.delete(id)
        }
    }
}
