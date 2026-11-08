package app.iptv.feature.player

import android.os.SystemClock
import app.iptv.core.domain.error.IptvError
import app.iptv.core.domain.model.Channel
import app.iptv.core.domain.model.EpgProgram
import app.iptv.core.domain.model.PlaybackConfig
import app.iptv.core.domain.repository.StreamResolver
import app.iptv.core.domain.util.Backoff
import app.iptv.core.domain.util.StreamFormat
import app.iptv.core.domain.util.StreamHeaders
import app.iptv.feature.player.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface PlayerStatus {
    data object Loading : PlayerStatus
    data object Active : PlayerStatus
    data class Reconnecting(val attempt: Int, val max: Int) : PlayerStatus
    data class Failed(val message: String) : PlayerStatus
}

/**
 * Plays one channel on one engine and keeps it alive: resolves the stream URL, reconnects with exponential
 * backoff on errors / stalls / dropped streams, and recovers from live-window and format problems.
 * Used by the full-screen player and by every multi-view pane. Call from the main thread.
 */
class ChannelPlayer(
    val engine: PlayerEngine,
    private val resolver: StreamResolver,
    private val config: PlaybackConfig,
    private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow<PlayerStatus>(PlayerStatus.Loading)
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()
    private val _channel = MutableStateFlow<Channel?>(null)
    val channel: StateFlow<Channel?> = _channel.asStateFlow()
    private val _catchupStart = MutableStateFlow<Long?>(null)
    val catchupStart: StateFlow<Long?> = _catchupStart.asStateFlow()

    private var catchup: Pair<Long, Long>? = null
    private var beginJob: Job? = null
    private var retryJob: Job? = null
    private val observers = mutableListOf<Job>()
    private var attempt = 0
    private var readySince = 0L
    private var forcedFormat: StreamFormat? = null
    private var released = false
    private var direct: Direct? = null
    private var resumePos = 0L

    private data class Direct(val url: String, val title: String)

    init { observe() }

    /**
     * Plays a plain URL (movie/episode) from [startPositionMs]. Reconnects resume from where the stream dropped,
     * and reaching the end is NOT treated as a dropped stream.
     */
    fun playDirect(url: String, title: String, startPositionMs: Long = 0) {
        if (released) return
        beginJob?.cancel(); retryJob?.cancel()
        attempt = 0; readySince = 0L; forcedFormat = null
        catchup = null; _catchupStart.value = null; _channel.value = null
        direct = Direct(url, title)
        resumePos = startPositionMs
        _status.value = PlayerStatus.Loading
        beginJob = scope.launch { beginDirect(useCurrentPosition = false) }
    }

    fun play(channel: Channel, catchup: Pair<Long, Long>? = null) {
        if (released) return
        direct = null
        beginJob?.cancel(); retryJob?.cancel()
        attempt = 0; readySince = 0L; forcedFormat = null
        this.catchup = catchup
        _catchupStart.value = catchup?.first
        _channel.value = channel
        _status.value = PlayerStatus.Loading
        beginJob = scope.launch { begin(channel) }
    }

    /** Manual retry after the automatic ones gave up. */
    fun retry() {
        val d = direct
        if (d != null) playDirect(d.url, d.title, engine.state.value.positionMs.takeIf { it > 0 } ?: resumePos)
        else _channel.value?.let { play(it, catchup) }
    }

    fun release() {
        released = true
        beginJob?.cancel(); retryJob?.cancel()
        observers.forEach { it.cancel() }
        engine.release()
    }

    // ---------------------------------------------------------------- internals

    private fun beginDirect(useCurrentPosition: Boolean) {
        val d = direct ?: return
        if (useCurrentPosition) engine.state.value.positionMs.takeIf { it > 0 }?.let { resumePos = it }
        val (clean, headers) = StreamHeaders.decode(d.url)
        engine.prepare(
            PlaybackSource(clean, headers, isLive = false, title = d.title, format = forcedFormat ?: StreamFormat.detect(clean)),
            resumePos
        )
    }

    private suspend fun begin(ch: Channel) {
        val url = try {
            val c = catchup
            if (c != null) {
                resolver.resolveCatchup(ch, EpgProgram(channelTvgId = "", title = "", description = null, startTime = c.first, endTime = c.second))
                    ?: throw IptvError.InvalidInput("Catch-up isn't available for this channel.")
            } else resolver.resolveLive(ch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onResolveFailure(e); return
        }
        val (clean, headers) = StreamHeaders.decode(url)
        // isLive = "don't cache, start at the live edge": true for catch-up too (timeshift streams are live-style TS).
        engine.prepare(PlaybackSource(clean, headers, isLive = true, title = ch.name, format = forcedFormat ?: StreamFormat.detect(clean)))
    }

    private fun observe() {
        observers += scope.launch {
            engine.state.map { it.error }.distinctUntilChanged().filterNotNull().collect { onPlaybackError(it) }
        }
        observers += scope.launch {
            engine.state.map { it.status }.distinctUntilChanged().collectLatest { st ->
                when (st) {
                    EngineStatus.READY -> {
                        if (readySince == 0L) readySince = SystemClock.elapsedRealtime()
                        if (_status.value is PlayerStatus.Loading || _status.value is PlayerStatus.Reconnecting) _status.value = PlayerStatus.Active
                    }
                    // A stream that just stops delivering never throws: long buffering counts as a drop.
                    EngineStatus.BUFFERING -> { delay(config.stallTimeoutMs); scheduleRetry("The stream stopped delivering data.") }
                    EngineStatus.ENDED -> onEnded()
                    EngineStatus.IDLE -> Unit
                }
            }
        }
    }

    private fun onEnded() {
        if (direct != null || _status.value is PlayerStatus.Failed) return // a movie ending is normal
        val ch = _channel.value ?: return
        // Catch-up reached the end of the programme: carry on live. A live stream "ending" means it dropped.
        if (catchup != null) play(ch, null) else scheduleRetry("The stream ended.")
    }

    private fun onPlaybackError(err: PlaybackError) {
        when {
            err.behindLiveWindow -> engine.restartLive()
            // e.g. HLS served from a URL without ".m3u8": retry once with an explicit hint
            err.unrecognizedFormat && forcedFormat == null &&
                StreamFormat.detect(direct?.url ?: _channel.value?.streamUrl.orEmpty()) != StreamFormat.HLS -> {
                forcedFormat = StreamFormat.HLS
                if (direct != null) beginDirect(useCurrentPosition = true)
                else _channel.value?.let { ch -> beginJob = scope.launch { begin(ch) } }
            }
            else -> scheduleRetry(err.message)
        }
    }

    private fun onResolveFailure(e: Throwable) {
        val err = e as? IptvError ?: IptvError.Unknown(e)
        when (err) {
            is IptvError.Network, is IptvError.Server, is IptvError.InvalidResponse, is IptvError.Unknown -> scheduleRetry(err.message)
            else -> fail(err.message ?: "Playback failed.")
        }
    }

    private fun scheduleRetry(reason: String?) {
        if (released || _status.value is PlayerStatus.Failed) return
        // Played fine for a while before dropping? Then it's a new incident, not the same retry streak.
        if (readySince != 0L && SystemClock.elapsedRealtime() - readySince > STABLE_MS) attempt = 0
        readySince = 0L
        retryJob?.cancel()
        if (attempt >= config.maxRetries) { fail(reason ?: "Playback failed."); return }
        attempt++
        _status.value = PlayerStatus.Reconnecting(attempt, config.maxRetries)
        retryJob = scope.launch {
            delay(Backoff.delayMs(attempt, config.retryBaseDelayMs, config.retryMaxDelayMs))
            if (direct != null) beginDirect(useCurrentPosition = true) else _channel.value?.let { begin(it) }
        }
    }

    private fun fail(message: String) {
        retryJob?.cancel()
        engine.stop()
        _status.value = PlayerStatus.Failed(message)
    }

    private companion object { const val STABLE_MS = 15_000L }
}
