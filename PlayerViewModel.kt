package app.iptv.feature.player

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.*
import app.iptv.core.domain.repository.ParentalGate
import app.iptv.core.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import app.iptv.core.domain.repository.ChannelRepository
import app.iptv.core.domain.repository.EpgRepository
import app.iptv.core.domain.repository.RecordingController
import app.iptv.core.domain.repository.RecordingRepository
import app.iptv.core.domain.repository.StreamResolver
import app.iptv.core.domain.repository.UserDataRepository
import app.iptv.core.domain.util.EpgKey
import app.iptv.core.domain.util.SeekStepper
import app.iptv.feature.player.engine.EngineState
import app.iptv.feature.player.engine.ExternalSubtitle
import app.iptv.feature.player.engine.PlayerEngine
import app.iptv.feature.player.engine.PlayerEngineFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** HIDDEN: nothing. INFO: channel banner (shown on zap, never takes focus). CONTROLS: banner + buttons (OK key). */
enum class OverlayMode { HIDDEN, INFO, CONTROLS }
enum class PlayerMenu { AUDIO, SUBTITLES, ASPECT }

data class PlayerUiState(
    val channel: Channel? = null,
    val status: PlayerStatus = PlayerStatus.Loading,
    val nowNext: List<EpgProgram> = emptyList(),
    val overlay: OverlayMode = OverlayMode.HIDDEN,
    val menu: PlayerMenu? = null,
    val aspect: AspectMode = AspectMode.FIT,
    val catchupStart: Long? = null,
    val seekFeedback: String? = null,
    /** The channel being watched is being recorded right now. */
    val recording: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val channels: ChannelRepository,
    private val epg: EpgRepository,
    private val userData: UserDataRepository,
    resolver: StreamResolver,
    engineFactory: PlayerEngineFactory,
    private val recordings: RecordingRepository,
    private val recorder: RecordingController,
    private val pip: PipState,
    prefs: PreferencesRepository,
    private val parental: ParentalGate,
    @ApplicationContext context: Context
) : ViewModel() {

    /** User settings (buffer, decoder, default aspect, subtitle style) read once when the player opens. */
    val settings: AppSettings = runBlocking { prefs.settings.first() }
    private val config = settings.toPlaybackConfig()
    /** One engine for the whole screen: zapping re-prepares it instead of creating a new player. Owns the MediaSession. */
    val engine: PlayerEngine = engineFactory.create(config, handleAudioFocus = true, withSession = true)
    val engineState: StateFlow<EngineState> = engine.state
    private val player = ChannelPlayer(engine, resolver, config, viewModelScope)
    val inPip: StateFlow<Boolean> = pip.inPip

    private val originalChannelId: Long = handle.get<Long>("channelId") ?: -1L
    private var currentId: Long = handle.get<Long>("current") ?: originalChannelId
    private val initialCatchup: Pair<Long, Long>? = run {
        val s = handle.get<Long>("programStart")?.takeIf { it >= 0 }
        val e = handle.get<Long>("programEnd")?.takeIf { it >= 0 }
        if (s != null && e != null && currentId == originalChannelId) s to e else null
    }

    private val _ui = MutableStateFlow(PlayerUiState(catchupStart = initialCatchup?.first, aspect = settings.defaultAspect))
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var overlayJob: Job? = null
    private var seekJob: Job? = null
    private var zapIds: List<Long> = emptyList()
    private var seekStep = 0L
    private var lastSeekAt = 0L
    private var resumeOnForeground = false
    private val backgroundAudio: BackgroundAudio? = if (config.backgroundAudio) BackgroundAudio.connect(context) else null

    init {
        viewModelScope.launch {
            player.status.collect { s ->
                _ui.update { it.copy(status = s, overlay = if (s is PlayerStatus.Failed) OverlayMode.HIDDEN else it.overlay) }
            }
        }
        viewModelScope.launch { player.catchupStart.collect { c -> _ui.update { it.copy(catchupStart = c) } } }
        startChannel(currentId, initialCatchup)
        observeNowNext()
        observePip()
        observeRecording()
    }

    // ---------------------------------------------------------------- channels

    private fun startChannel(channelId: Long, catchup: Pair<Long, Long>?) {
        currentId = channelId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ch = channels.getChannel(channelId)
            if (ch == null) { _ui.update { it.copy(status = PlayerStatus.Failed("This channel no longer exists.")) }; return@launch }
            handle["current"] = channelId
            _ui.update { it.copy(channel = ch, nowNext = emptyList()) }
            showInfo()
            player.play(ch, catchup)
            userData.saveProgress(
                WatchHistory(
                    type = ContentType.CHANNEL, contentKey = ch.id.toString(), playlistId = ch.playlistId, title = ch.name,
                    imageUrl = ch.logoUrl, streamUrl = ch.streamUrl, positionMs = 0, durationMs = 0, updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** Channel up/down within the current category, wrapping around. [next] = further down the list. */
    fun zap(next: Boolean) {
        viewModelScope.launch {
            var i = zapIds.indexOf(currentId)
            if (i < 0) {
                val ch = _ui.value.channel ?: return@launch
                zapIds = channels.orderedChannelIds(ch.playlistId, ch.categoryId)
                i = zapIds.indexOf(currentId)
            }
            if (i < 0 || zapIds.size < 2) return@launch
            // Zapping must not be a way around parental locks: skip channels that currently need the PIN.
            val step = if (next) 1 else -1
            val target = (1 until zapIds.size).map { zapIds[(i + step * it).mod(zapIds.size)] }
                .firstOrNull { !parental.channelNeedsPin(it) } ?: return@launch
            startChannel(target, null)
        }
    }

    fun retry() { if (_ui.value.channel == null) startChannel(currentId, initialCatchup) else player.retry() }

    // ---------------------------------------------------------------- transport

    fun togglePlayPause() { if (engine.state.value.playWhenReady) engine.pause() else engine.play(); showInfo() }
    fun resume() { engine.play(); showInfo() }
    fun pausePlayback() { engine.pause(); showInfo() }

    fun seek(forward: Boolean) {
        if (!engine.state.value.seekable) { showInfo(); return } // live: nothing to seek
        val now = SystemClock.elapsedRealtime()
        seekStep = SeekStepper.next(seekStep, now - lastSeekAt)
        lastSeekAt = now
        engine.seekBy(if (forward) seekStep else -seekStep)
        _ui.update { it.copy(seekFeedback = (if (forward) "+" else "-") + (seekStep / 1000) + "s") }
        seekJob?.cancel()
        seekJob = viewModelScope.launch { delay(1_000); _ui.update { it.copy(seekFeedback = null) } }
        showInfo()
    }

    fun onBackgrounded() {
        if (pip.inPip.value) return // the PiP window keeps playing
        if (config.backgroundAudio) { engine.setVideoEnabled(false); return }
        resumeOnForeground = engine.state.value.playWhenReady
        engine.pause()
    }

    fun onForegrounded() {
        if (config.backgroundAudio) engine.setVideoEnabled(true)
        if (resumeOnForeground) engine.play()
        resumeOnForeground = false
    }

    // ---------------------------------------------------------------- overlay & menus

    fun showInfo() { if (_ui.value.overlay == OverlayMode.CONTROLS) pokeOverlay() else setOverlay(OverlayMode.INFO) }
    fun showControls() = setOverlay(OverlayMode.CONTROLS)
    fun pokeOverlay() { val m = _ui.value.overlay; if (m != OverlayMode.HIDDEN) setOverlay(m) }
    fun hideOverlay() { overlayJob?.cancel(); _ui.update { it.copy(overlay = OverlayMode.HIDDEN) } }

    private fun setOverlay(mode: OverlayMode) {
        _ui.update { it.copy(overlay = mode) }
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            delay(if (mode == OverlayMode.CONTROLS) CONTROLS_MS else INFO_MS)
            if (_ui.value.menu == null) _ui.update { it.copy(overlay = OverlayMode.HIDDEN) }
        }
    }

    fun openMenu(menu: PlayerMenu) { overlayJob?.cancel(); _ui.update { it.copy(menu = menu, overlay = OverlayMode.CONTROLS) } }
    fun closeMenu() { _ui.update { it.copy(menu = null) }; setOverlay(OverlayMode.CONTROLS) }

    // ---------------------------------------------------------------- tracks, aspect, subtitles

    fun selectAudio(id: String) = engine.selectAudio(id)
    fun selectSubtitle(id: String?) = engine.selectText(id)
    fun setAspect(mode: AspectMode) { _ui.update { it.copy(aspect = mode) } }

    fun addSubtitle(uri: String, displayName: String) {
        engine.addExternalSubtitle(ExternalSubtitle(uri = uri, mimeType = subtitleMime(displayName), label = displayName.substringBeforeLast('.')))
    }

    // ---------------------------------------------------------------- EPG banner & Picture-in-Picture

    private fun observeNowNext() {
        viewModelScope.launch {
            combine(_ui.map { it.channel }.distinctUntilChanged { a, b -> a?.id == b?.id }, minuteClock()) { ch, now -> ch to now }
                .flatMapLatest { (ch, now) ->
                    if (ch == null) flowOf(emptyList<EpgProgram>())
                    else epg.observeNowAndNext(EpgKey.of(ch.tvgId, ch.tvgName, ch.name), now)
                }
                .collect { list -> _ui.update { it.copy(nowNext = list) } }
        }
    }

    private fun observePip() {
        pip.onTogglePlayPause = { if (engine.state.value.playWhenReady) engine.pause() else engine.play() }
        viewModelScope.launch {
            combine(engine.state, ui) { e, u -> Triple(e.videoAspectRatio, e.playWhenReady, u.status !is PlayerStatus.Failed) }
                .distinctUntilChanged()
                .collect { (ratio, playing, ok) -> pip.update(eligible = ok, aspect = ratio, playing = playing) }
        }
    }

    private var activeRecordingId: Long? = null

    private fun observeRecording() {
        viewModelScope.launch {
            combine(recordings.observeAll(), _ui.map { it.channel?.id }.distinctUntilChanged()) { list, channelId ->
                list.firstOrNull { it.status == RecordingStatus.RECORDING && it.channelId == channelId }
            }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { r ->
                activeRecordingId = r?.id
                _ui.update { it.copy(recording = r != null) }
            }
        }
    }

    /** Starts recording the current programme (or the next hour when there is no guide data); pressing again stops it. */
    fun toggleRecord() {
        viewModelScope.launch {
            val running = activeRecordingId
            if (running != null) { recorder.stop(running); return@launch }
            val ch = _ui.value.channel ?: return@launch
            val now = System.currentTimeMillis()
            val current = _ui.value.nowNext.firstOrNull { it.isLiveAt(now) }
            recorder.recordNow(ch, current?.title ?: ch.name, current?.endTime ?: (now + DEFAULT_RECORD_MS))
            _ui.update { it.copy(seekFeedback = "● REC") }
            seekJob?.cancel()
            seekJob = launch { delay(1_500); _ui.update { it.copy(seekFeedback = null) } }
        }
    }

    private fun minuteClock() = flow {
        while (true) {
            val n = System.currentTimeMillis()
            emit(n / 60_000 * 60_000)
            delay(60_000 - n % 60_000)
        }
    }

    override fun onCleared() {
        pip.clear()
        backgroundAudio?.release()
        player.release() // releases the engine too
        super.onCleared()
    }

    private companion object {
        const val INFO_MS = 4_000L
        const val CONTROLS_MS = 6_000L
        const val DEFAULT_RECORD_MS = 60L * 60 * 1000
    }
}
