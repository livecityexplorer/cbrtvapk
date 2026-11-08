package app.iptv.feature.epg

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.iptv.core.domain.error.IptvError
import app.iptv.core.domain.model.*
import app.iptv.core.domain.reminder.ReminderRequest
import app.iptv.core.domain.reminder.ReminderScheduler
import app.iptv.core.domain.repository.*
import app.iptv.core.domain.sync.EpgSyncResult
import app.iptv.core.domain.sync.EpgSyncer
import app.iptv.core.domain.util.EpgKey
import app.iptv.core.domain.util.RecordingConflicts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface EpgRefreshUi {
    data object Idle : EpgRefreshUi
    data class Running(val programs: Int) : EpgRefreshUi
    data class Done(val result: EpgSyncResult) : EpgRefreshUi
    data class Failed(val error: IptvError) : EpgRefreshUi
}

data class EpgUiState(
    val loading: Boolean = true,
    val active: Playlist? = null,
    val categories: List<Category> = emptyList(),
    /** null = all channels */
    val categoryId: Long? = null,
    val refresh: EpgRefreshUi = EpgRefreshUi.Idle
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EpgViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val epgSyncer: EpgSyncer,
    private val reminders: ReminderScheduler,
    private val recordings: RecordingRepository,
    private val recorder: RecordingController,
    private val prefs: PreferencesRepository
) : ViewModel() {

    /** The grid's time axis: from 3 h ago (catch-up) to 27 h after that, on half-hour boundaries. */
    val windowStart: Long = (System.currentTimeMillis() - 3 * HOUR) / HALF_HOUR * HALF_HOUR
    val windowEnd: Long = windowStart + WINDOW_HOURS * HOUR

    private val categoryKey = handle.getStateFlow(CAT, -1L)
    private val refresh = MutableStateFlow<EpgRefreshUi>(EpgRefreshUi.Idle)

    private val playlists: StateFlow<List<Playlist>?> =
        playlistRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val active: StateFlow<Playlist?> =
        combine(playlists, prefs.activePlaylistId) { list, stored ->
            list?.let { l -> l.firstOrNull { it.id == stored } ?: l.firstOrNull() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val categories: Flow<List<Category>> =
        active.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(emptyList<Category>())
            else channelRepository.observeCategories(id, CategoryKind.LIVE).map { l -> l.filterNot { it.isHidden } }
        }

    val uiState: StateFlow<EpgUiState> = combine(playlists, active, categories, categoryKey, refresh) { all, a, cats, cat, r ->
        EpgUiState(loading = all == null, active = a, categories = cats, categoryId = cat.takeIf { it >= 0 }, refresh = r)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EpgUiState())

    val channels: Flow<PagingData<Channel>> =
        combine(active.map { it?.id }.distinctUntilChanged(), categoryKey) { pid, cat -> pid to cat }
            .flatMapLatest { (pid, cat) ->
                if (pid == null) flowOf(PagingData.empty<Channel>())
                else channelRepository.channels(pid, cat.takeIf { it >= 0 }, "")
            }.cachedIn(viewModelScope)

    init {
        // Refresh automatically when the guide is stale (background work also does this periodically).
        viewModelScope.launch {
            active.filterNotNull().first()
            val last = prefs.lastEpgRefresh.first()
            if (System.currentTimeMillis() - last > STALE_MS) refreshEpg()
        }
    }

    fun selectCategory(id: Long?) { handle[CAT] = id ?: -1L }

    fun programs(channel: Channel): Flow<List<EpgProgram>> =
        epgRepository.observeRange(listOf(EpgKey.of(channel.tvgId, channel.tvgName, channel.name)), windowStart, windowEnd)

    fun reminderScheduled(channel: Channel, program: EpgProgram): Flow<Boolean> =
        reminders.observeScheduled(channel.id, program.startTime)

    fun setReminder(channel: Channel, program: EpgProgram): Boolean =
        reminders.schedule(ReminderRequest(channel.id, channel.name, program.title, program.startTime))

    fun cancelReminder(channel: Channel, program: EpgProgram) = reminders.cancel(channel.id, program.startTime)

    /** The scheduled/running recording of this programme, if any. */
    fun recordingFor(channel: Channel, program: EpgProgram): Flow<Recording?> =
        recordings.observeAll().map { list ->
            list.firstOrNull { it.channelId == channel.id && it.startTime == program.startTime && it.status in RecordingConflicts.ACTIVE }
        }

    suspend fun record(channel: Channel, program: EpgProgram): ScheduleResult = recorder.scheduleProgram(channel, program)
    fun stopRecording(id: Long) { viewModelScope.launch { recorder.stop(id) } }

    fun refreshEpg() {
        val id = active.value?.id ?: return
        if (refresh.value is EpgRefreshUi.Running) return
        viewModelScope.launch {
            refresh.value = EpgRefreshUi.Running(0)
            refresh.value = try {
                EpgRefreshUi.Done(epgSyncer.refresh(id) { n -> refresh.value = EpgRefreshUi.Running(n) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                EpgRefreshUi.Failed(e as? IptvError ?: IptvError.Unknown(e))
            }
        }
    }

    private companion object {
        const val CAT = "cat"
        const val HOUR = 3_600_000L
        const val HALF_HOUR = 1_800_000L
        const val WINDOW_HOURS = 27L
        const val STALE_MS = 12 * HOUR
    }
}
