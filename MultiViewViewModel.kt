package app.iptv.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.iptv.core.domain.model.Channel
import app.iptv.core.domain.model.PlaybackConfig
import app.iptv.core.domain.model.DecoderMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import app.iptv.core.domain.repository.ChannelRepository
import app.iptv.core.domain.repository.PlaylistRepository
import app.iptv.core.domain.repository.PreferencesRepository
import app.iptv.core.domain.repository.StreamResolver
import app.iptv.core.domain.util.ActivePlaylist
import app.iptv.core.domain.util.MultiViewLayout
import app.iptv.feature.player.engine.PlayerEngine
import app.iptv.feature.player.engine.PlayerEngineFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One multi-view tile: its own engine + reconnecting channel player. Only the focused pane is audible. */
class Pane(val id: Int, val player: ChannelPlayer) {
    val engine: PlayerEngine get() = player.engine

    /** Audio focus is only requested by the audible pane, otherwise the players would pause each other. */
    fun setAudible(audible: Boolean) {
        engine.setVolume(if (audible) 1f else 0f)
        engine.setAudioFocusHandling(audible)
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MultiViewViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val channels: ChannelRepository,
    playlists: PlaylistRepository,
    prefs: PreferencesRepository,
    private val resolver: StreamResolver,
    private val engineFactory: PlayerEngineFactory
) : ViewModel() {

    // Smaller buffers and fewer retries than the full-screen player: up to 4 streams share the device and the connection.
    private val paneConfig = PlaybackConfig(
        minBufferMs = 5_000, maxBufferMs = 15_000, startBufferMs = 1_000, rebufferMs = 2_000, maxRetries = 3,
        preferSoftwareDecoder = runBlocking { prefs.settings.first() }.decoder == DecoderMode.SOFTWARE
    )

    private val _panes = MutableStateFlow<List<Pane>>(emptyList())
    val panes: StateFlow<List<Pane>> = _panes.asStateFlow()
    private val _focusedId = MutableStateFlow<Int?>(null)
    val focusedId: StateFlow<Int?> = _focusedId.asStateFlow()
    private val _expandedId = MutableStateFlow<Int?>(null)
    val expandedId: StateFlow<Int?> = _expandedId.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    /** false until the panes saved/requested at start are loaded (avoids flashing the channel picker). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var nextId = 0

    init {
        viewModelScope.launch {
            val saved = handle.get<LongArray>(KEY)?.toList()
            val ids = saved ?: listOfNotNull(handle.get<Long>("channelId")?.takeIf { it >= 0 })
            ids.forEach { id -> channels.getChannel(id)?.let { add(it) } }
            _loaded.value = true
        }
    }

    // ---------------------------------------------------------------- panes

    fun add(channel: Channel): Boolean {
        if (_panes.value.size >= MultiViewLayout.MAX_PANES) return false
        val engine = engineFactory.create(paneConfig, handleAudioFocus = false, withSession = false)
        val pane = Pane(nextId++, ChannelPlayer(engine, resolver, paneConfig, viewModelScope))
        _panes.update { it + pane }
        if (_focusedId.value == null) setFocus(pane.id) else pane.setAudible(false)
        pane.player.play(channel)
        persist()
        return true
    }

    fun replace(paneId: Int, channel: Channel) {
        _panes.value.firstOrNull { it.id == paneId }?.player?.play(channel)
        persist()
    }

    fun remove(paneId: Int) {
        val pane = _panes.value.firstOrNull { it.id == paneId } ?: return
        _panes.update { list -> list.filterNot { it.id == paneId } }
        pane.player.release()
        if (_expandedId.value == paneId) _expandedId.value = null
        if (_focusedId.value == paneId) {
            _focusedId.value = null
            _panes.value.firstOrNull()?.let { setFocus(it.id) }
        }
        persist()
    }

    fun setFocus(paneId: Int) {
        if (_focusedId.value == paneId) return
        _focusedId.value = paneId
        _panes.value.forEach { it.setAudible(it.id == paneId) }
    }

    fun toggleExpand(paneId: Int) {
        _expandedId.value = if (_expandedId.value == paneId) null else paneId
        setFocus(paneId)
    }

    fun onBackgrounded() { _panes.value.forEach { it.engine.pause() } }
    fun onForegrounded() { _panes.value.forEach { it.engine.play() } }

    private fun persist() {
        handle[KEY] = _panes.value.mapNotNull { it.player.channel.value?.id }.toLongArray()
    }

    // ---------------------------------------------------------------- channel picker

    private val pickerQuery = MutableStateFlow("")
    val query: StateFlow<String> = pickerQuery.asStateFlow()
    fun setQuery(q: String) { pickerQuery.value = q }

    private val activePlaylistId: Flow<Long?> =
        combine(playlists.observeAll(), prefs.activePlaylistId) { list, stored -> ActivePlaylist.resolve(list, stored)?.id }.distinctUntilChanged()

    val pickerChannels: Flow<PagingData<Channel>> =
        combine(activePlaylistId, pickerQuery.debounce { if (it.isEmpty()) 0L else 300L }) { id, q -> id to q.trim() }
            .flatMapLatest { (id, q) ->
                if (id == null) flowOf(PagingData.empty<Channel>()) else channels.channels(id, null, q)
            }.cachedIn(viewModelScope)

    override fun onCleared() {
        _panes.value.forEach { it.player.release() }
        super.onCleared()
    }

    private companion object { const val KEY = "panes" }
}
