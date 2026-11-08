@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv.feature.player.engine

import android.view.SurfaceView
import androidx.media3.common.text.Cue
import app.iptv.core.domain.util.StreamFormat
import kotlinx.coroutines.flow.StateFlow

enum class EngineStatus { IDLE, BUFFERING, READY, ENDED }
enum class ErrorKind { NETWORK, SOURCE, DECODER, OTHER }

data class PlaybackError(
    val code: Int, val kind: ErrorKind, val message: String, val httpStatus: Int? = null,
    /** Live window moved past us: seek back to the live edge instead of treating it as a failure. */
    val behindLiveWindow: Boolean = false,
    /** Container not recognized (e.g. HLS served from a URL without .m3u8): retry with a format hint. */
    val unrecognizedFormat: Boolean = false
)

data class TrackOption(val id: String, val label: String, val language: String?, val selected: Boolean)

data class ExternalSubtitle(val uri: String, val mimeType: String, val label: String, val language: String? = null)

data class PlaybackSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isLive: Boolean = true,
    val title: String = "",
    val format: StreamFormat = StreamFormat.UNKNOWN,
    val subtitles: List<ExternalSubtitle> = emptyList()
)

data class EngineState(
    val status: EngineStatus = EngineStatus.IDLE,
    val playWhenReady: Boolean = true,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long = -1, val bufferedMs: Long = 0,
    val isLive: Boolean = false, val seekable: Boolean = false,
    /** Display aspect ratio (width * pixelRatio / height); 0 = unknown yet. */
    val videoAspectRatio: Float = 0f,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val textEnabled: Boolean = false,
    val cues: List<Cue> = emptyList(),
    val error: PlaybackError? = null
)

/**
 * One playback pane. ExoPlayer is the only implementation today; the interface is what a libmpv engine would
 * implement. All calls must be made on the main thread. Multi-view (step 9) creates several engines.
 */
interface PlayerEngine {
    val state: StateFlow<EngineState>
    fun prepare(source: PlaybackSource, startPositionMs: Long = 0)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    /** Jump back to the live edge and re-prepare (after BehindLiveWindow). */
    fun restartLive()
    fun setVolume(volume: Float)
    /** Only one pane at a time should hold audio focus (multi-view); others stay muted and unfocused. */
    fun setAudioFocusHandling(enabled: Boolean)
    /** Turn video decoding off (background audio) / back on. */
    fun setVideoEnabled(enabled: Boolean)
    fun selectAudio(trackId: String)
    /** null turns subtitles off. */
    fun selectText(trackId: String?)
    fun addExternalSubtitle(subtitle: ExternalSubtitle)
    fun attachVideoView(view: SurfaceView)
    fun detachVideoView(view: SurfaceView)
    fun release()
}
