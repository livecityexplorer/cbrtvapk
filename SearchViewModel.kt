package app.iptv.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.Channel
import app.iptv.core.domain.model.EpgProgram
import app.iptv.core.domain.model.Playlist
import app.iptv.core.domain.model.SearchResults
import app.iptv.core.domain.reminder.ReminderRequest
import app.iptv.core.domain.reminder.ReminderScheduler
import app.iptv.core.domain.repository.PlaylistRepository
import app.iptv.core.domain.repository.PreferencesRepository
import app.iptv.core.domain.repository.SearchRepository
import app.iptv.core.domain.util.ActivePlaylist
import app.iptv.core.domain.util.SearchQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import javax.inject.Inject

enum class SearchStatus { NO_PLAYLIST, IDLE, SEARCHING, DONE, ERROR }

data class SearchUi(
    val status: SearchStatus = SearchStatus.IDLE,
    val results: SearchResults = SearchResults(),
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    handle: SavedStateHandle,
    playlistRepository: PlaylistRepository,
    prefs: PreferencesRepository,
    private val search: SearchRepository,
    private val reminders: ReminderScheduler
) : ViewModel() {

    val query: StateFlow<String> = handle.getStateFlow(QUERY, "")
    private val handleRef = handle

    private val playlists: StateFlow<List<Playlist>?> =
        playlistRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Active(val loaded: Boolean, val playlistId: Long?)

    private val active: Flow<Active> = combine(playlists, prefs.activePlaylistId) { list, stored ->
        if (list == null) Active(false, null) else Active(true, ActivePlaylist.resolve(list, stored)?.id)
    }.distinctUntilChanged()

    /** Previous results stay on screen while the next search runs (no flicker between keystrokes). */
    private var lastResults = SearchResults()

    val ui: StateFlow<SearchUi> = combine(
        active,
        // clearing the box reacts instantly; typing waits for a pause
        query.debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }.map { it.trim() }.distinctUntilChanged()
    ) { a, q -> a to q }
        .transformLatest { (a, q) ->
            val id = a.playlistId
            when {
                !a.loaded -> emit(SearchUi(SearchStatus.IDLE))
                id == null -> emit(SearchUi(SearchStatus.NO_PLAYLIST))
                SearchQuery.parse(q) == null -> { lastResults = SearchResults(); emit(SearchUi(SearchStatus.IDLE)) }
                else -> {
                    emit(SearchUi(SearchStatus.SEARCHING, lastResults))
                    try {
                        val r = search.search(id, q)
                        lastResults = r
                        emit(SearchUi(SearchStatus.DONE, r))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        emit(SearchUi(SearchStatus.ERROR, error = e.message ?: "Unknown error"))
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUi())

    fun setQuery(q: String) { handleRef[QUERY] = q }

    fun reminderScheduled(channel: Channel, program: EpgProgram): Flow<Boolean> = reminders.observeScheduled(channel.id, program.startTime)
    fun setReminder(channel: Channel, program: EpgProgram): Boolean =
        reminders.schedule(ReminderRequest(channel.id, channel.name, program.title, program.startTime))
    fun cancelReminder(channel: Channel, program: EpgProgram) = reminders.cancel(channel.id, program.startTime)

    private companion object {
        const val QUERY = "q"
        const val DEBOUNCE_MS = 300L
    }
}
