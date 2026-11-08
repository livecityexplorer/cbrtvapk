package app.iptv.feature.player

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.*
import app.iptv.core.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import app.iptv.core.domain.repository.ChannelRepository
import app.iptv.core.domain.repository.RecordingRepository
import app.iptv.core.domain.repository.StreamResolver
import app.iptv.core.domain.repository.UserDataRepository
import app.iptv.core.domain.repository.VodRepository
import app.iptv.core.domain.util.ResumePolicy
import app.iptv.core.domain.util.SeekStepper
import app.iptv.feature.player.engine.EngineState
import app.iptv.feature.player.engine.EngineStatus
import app.iptv.feature.player.engine.ExternalSubtitle
import app.iptv.feature.player.engine.PlayerEngine
import app.iptv.feature.player.engine.PlayerEngineFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VodPlayerUi(
    val title: String = "",
    val subtitle: String? = null,
    val status: PlayerStatus = PlayerStatus.Loading,
    val controls: Boolean = false,
    val menu: PlayerMenu? = null,
    val aspect: AspectMode = AspectMode.FIT,
    val hasNext: Boolean = false,
    val seekFeedback: String? = null,
    /** Playback reached the end and there is nothing queued after it. */
    val finished: Boolean = false
)

/** Movie / episode playback with seeking, resume and auto-play of the next episode. */
@HiltViewModel
class VodPlayerViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val vod: VodRepository,
    private val userData: UserDataRepository,
    private val recordings: RecordingRepository,
    private val channels: ChannelRepository,
    resolver: StreamResolver,
    engineFactory: PlayerEngineFactory,
    private val pip: PipState,
    prefs: PreferencesRepository,
    @ApplicationContext context: Context
) : ViewModel() {

    val settings: AppSettings = runBlocking { prefs.settings.first() }

    private data class Media(
        val type: ContentType, val id: Long, val playlistId: Long, val title: String, val subtitle: String?,
        val imageUrl: String?, val url: String, val seriesId: Long?
    )

    // Bigger buffers than live (seeking back and forth) and a longer stall timeout (slow servers).
    private val config = run {
        val vodBase = PlaybackConfig(stallTimeoutMs = 30_000, minBufferMs = 20_000, maxBufferMs = 60_000, startBufferMs = 2_500, rebufferMs = 5_000)
        val c = settings.toPlaybackConfig(vodBase)
        c.copy(
            minBufferMs = maxOf(c.minBufferMs, vodBase.minBufferMs), maxBufferMs = maxOf(c.maxBufferMs, vodBase.maxBufferMs),
            startBufferMs = maxOf(c.startBufferMs, vodBase.startBufferMs), rebufferMs = maxOf(c.rebufferMs, vodBase.rebufferMs)
        )
    }
    val engine: PlayerEngine = engineFactory.create(config, handleAudioFocus = true, withSession = true)
    val engineState: StateFlow<EngineState> = engine.state
    private val player = ChannelPlayer(engine, resolver, config, viewModelScope)
    val inPip: StateFlow<Boolean> = pip.inPip

    private val _ui = MutableStateFlow(VodPlayerUi(aspect = settings.defaultAspect))
    val ui: StateFlow<VodPlayerUi> = _ui.asStateFlow()

    private var media: Media? = null
    private var episodes: List<Episode> = emptyList()
    private var nextEpisode: Episode? = null
    private var loadJob: Job? = null
    private var controlsJob: Job? = null
    private var seekJob: Job? = null
    private var seekStep = 0L
    private var lastSeekAt = 0L
    private var resumeOnForeground = false
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO) // for the final save after the VM is cleared

    init {
        viewModelScope.launch { player.status.collect { s -> _ui.update { it.copy(status = s) } } }
        // "resume" only applies to the title the screen was opened with; switching episodes always starts at the beginning
        val kind = handle.get<String>("curKind") ?: handle.get<String>("kind").orEmpty()
        val id = handle.get<Long>("curId") ?: handle.get<Long>("id") ?: -1L
        val resume = if (handle.contains("curId")) true else handle.get<Boolean>("resume") != false
        start(kind, id, resume)
        observeEnd()
        observePip()
        viewModelScope.launch { while (true) { delay(SAVE_EVERY_MS); if (engine.state.value.isPlaying) saveNow() } }
    }

    // ---------------------------------------------------------------- loading

    private fun start(kind: String, id: Long, resume: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            saveNow()
            val m = load(kind, id)
            if (m == null) { _ui.update { it.copy(status = PlayerStatus.Failed("This title is no longer available.")) }; return@launch }
            media = m
            handle["curKind"] = kind; handle["curId"] = id
            nextEpisode = null
            if (m.seriesId != null) {
                episodes = vod.observeEpisodes(m.seriesId).first()
                nextEpisode = episodes.getOrNull(episodes.indexOfFirst { it.id == m.id } + 1)?.takeIf { episodes.any { e -> e.id == m.id } }
            }
            val saved = if (resume) userData.getProgress(m.type, m.id.toString()) else null
            val startAt = saved?.let { ResumePolicy.startPosition(it.positionMs, it.durationMs) } ?: 0L
            _ui.update { it.copy(title = m.title, subtitle = m.subtitle, hasNext = nextEpisode != null, finished = false, status = PlayerStatus.Loading) }
            player.playDirect(m.url, m.title, startAt)
            showControls()
        }
    }

    private suspend fun load(kind: String, id: Long): Media? = when (kind) {
        "movie" -> vod.getMovie(id)?.let { Media(ContentType.MOVIE, it.id, it.playlistId, it.name, null, it.posterUrl, it.streamUrl, null) }
        "episode" -> vod.getEpisode(id)?.let { ep ->
            val series = vod.getSeries(ep.seriesId)
            val code = "S" + ep.season.toString().padStart(2, '0') + "E" + ep.episodeNum.toString().padStart(2, '0')
            Media(
                ContentType.EPISODE, ep.id, series?.playlistId ?: 0L,
                (series?.name?.let { "$it · " } ?: "") + code, ep.title, ep.imageUrl ?: series?.posterUrl, ep.streamUrl, ep.seriesId
            )
        }
        "recording" -> recordings.get(id)?.takeIf { it.filePath != null }?.let { r ->
            val channel = r.channelId?.let { channels.getChannel(it) } // gives resume support while the channel still exists
            val path = r.filePath!!
            Media(
                ContentType.RECORDING, r.id, channel?.playlistId ?: 0L, r.title, r.channelName, channel?.logoUrl,
                if (path.startsWith("/")) "file://$path" else path, null
            )
        }
        else -> null
    }

    fun playNext() {
        val n = nextEpisode ?: return
        start("episode", n.id, resume = false)
    }

    fun retry() = player.retry()

    // ---------------------------------------------------------------- progress

    private fun snapshot(): WatchHistory? {
        val m = media ?: return null
        if (m.playlistId <= 0) return null // history rows belong to a playlist
        val st = engine.state.value
        val duration = st.durationMs.takeIf { it > 0 } ?: return null // not known yet: don't overwrite the saved position with 0
        return WatchHistory(
            type = m.type, contentKey = m.id.toString(), playlistId = m.playlistId,
            title = m.title + (m.subtitle?.let { " – $it" } ?: ""), imageUrl = m.imageUrl, streamUrl = m.url,
            positionMs = st.positionMs.coerceIn(0, duration), durationMs = duration, updatedAt = System.currentTimeMillis()
        )
    }

    private fun saveNow() { snapshot()?.let { s -> viewModelScope.launch { userData.saveProgress(s) } } }

    private fun observeEnd() {
        viewModelScope.launch {
            engine.state.map { it.status }.distinctUntilChanged().filter { it == EngineStatus.ENDED }.collect {
                snapshot()?.let { s -> userData.saveProgress(s.copy(positionMs = s.durationMs)) } // mark as finished
                if (nextEpisode != null) playNext() else _ui.update { it.copy(finished = true) }
            }
        }
    }

    // ---------------------------------------------------------------- transport

    fun togglePlayPause() { if (engine.state.value.playWhenReady) engine.pause() else engine.play(); showControls() }
    fun resume() { engine.play(); showControls() }
    fun pausePlayback() { engine.pause(); showControls() }

    fun seek(forward: Boolean) {
        val now = SystemClock.elapsedRealtime()
        seekStep = SeekStepper.next(seekStep, now - lastSeekAt)
        lastSeekAt = now
        engine.seekBy(if (forward) seekStep else -seekStep)
        _ui.update { it.copy(seekFeedback = (if (forward) "+" else "-") + (seekStep / 1000) + "s") }
        seekJob?.cancel()
        seekJob = viewModelScope.launch { delay(1_000); _ui.update { it.copy(seekFeedback = null) } }
        pokeControls()
    }

    /** Touch: tap on the bar. */
    fun seekToFraction(fraction: Float) {
        val d = engine.state.value.durationMs
        if (d > 0) engine.seekTo((d * fraction.coerceIn(0f, 1f)).toLong())
        pokeControls()
    }

    fun onBackgrounded() {
        saveNow()
        if (pip.inPip.value) return
        resumeOnForeground = engine.state.value.playWhenReady
        engine.pause()
    }

    fun onForegrounded() { if (resumeOnForeground) engine.play(); resumeOnForeground = false }

    // ---------------------------------------------------------------- controls & menus

    fun showControls() { setControls(true) }
    fun pokeControls() { if (_ui.value.controls) setControls(true) }
    fun hideControls() { controlsJob?.cancel(); _ui.update { it.copy(controls = false) } }

    private fun setControls(visible: Boolean) {
        _ui.update { it.copy(controls = visible) }
        controlsJob?.cancel()
        if (visible) controlsJob = viewModelScope.launch {
            delay(CONTROLS_MS)
            if (_ui.value.menu == null) _ui.update { it.copy(controls = false) }
        }
    }

    fun openMenu(menu: PlayerMenu) { controlsJob?.cancel(); _ui.update { it.copy(menu = menu, controls = true) } }
    fun closeMenu() { _ui.update { it.copy(menu = null) }; setControls(true) }

    fun selectAudio(id: String) = engine.selectAudio(id)
    fun selectSubtitle(id: String?) = engine.selectText(id)
    fun setAspect(mode: AspectMode) { _ui.update { it.copy(aspect = mode) } }
    fun addSubtitle(uri: String, displayName: String) {
        engine.addExternalSubtitle(ExternalSubtitle(uri = uri, mimeType = subtitleMime(displayName), label = displayName.substringBeforeLast('.')))
    }

    // ---------------------------------------------------------------- Picture-in-Picture

    private fun observePip() {
        pip.onTogglePlayPause = { if (engine.state.value.playWhenReady) engine.pause() else engine.play() }
        viewModelScope.launch {
            combine(engine.state, ui) { e, u -> Triple(e.videoAspectRatio, e.playWhenReady, u.status !is PlayerStatus.Failed) }
                .distinctUntilChanged()
                .collect { (ratio, playing, ok) -> pip.update(eligible = ok, aspect = ratio, playing = playing) }
        }
    }

    override fun onCleared() {
        val last = snapshot() // before the engine is released
        pip.clear()
        player.release()
        last?.let { detached.launch { userData.saveProgress(it) } }
        super.onCleared()
    }

    private companion object {
        const val SAVE_EVERY_MS = 10_000L
        const val CONTROLS_MS = 6_000L
    }
}
