@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv.feature.player.engine

import android.app.PendingIntent
import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.*
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.MediaSession
import app.iptv.core.domain.model.PlaybackConfig
import app.iptv.core.domain.util.StreamFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class ExoPlayerEngine(
    context: Context,
    okHttp: OkHttpClient,
    cache: Cache?,
    config: PlaybackConfig,
    handleAudioFocus: Boolean = true,
    sessionHolder: PlayerSessionHolder? = null
) : PlayerEngine {

    private val extraHeaders = AtomicReference<Map<String, String>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val liveFactory: MediaSource.Factory
    private val vodFactory: MediaSource.Factory
    private val player: ExoPlayer
    private var currentSource: PlaybackSource? = null
    private val sessionHolderRef = sessionHolder
    private var session: MediaSession? = null
    private val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

    init {
        val http = OkHttpDataSource.Factory(okHttp).setUserAgent(USER_AGENT)
        // DefaultDataSource covers http(s) via OkHttp plus udp, content:// and (with the module) rtmp://.
        val upstream: DataSource.Factory = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
            val h = extraHeaders.get() // per-stream headers from #EXTVLCOPT
            if (h.isEmpty()) spec else spec.withAdditionalHeaders(h)
        }
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        // Live streams are never cached (a cached HLS playlist would be stale); VOD gets the on-disk cache.
        liveFactory = DefaultMediaSourceFactory(upstream, extractors)
        vodFactory = DefaultMediaSourceFactory(
            if (cache == null) upstream
            else CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
            extractors
        )

        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true).apply {
            if (config.preferSoftwareDecoder) setMediaCodecSelector(softwareFirst)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(config.minBufferMs, config.maxBufferMs, config.startBufferMs, config.rebufferMs)
            .build()
        player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(liveFactory)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener())

        if (sessionHolder != null) {
            // Media keys, Assistant and (when the service runs) the notification all go through this session.
            val builder = MediaSession.Builder(context, player).setId("iptv_" + System.nanoTime())
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                builder.setSessionActivity(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            session = builder.build().also { sessionHolder.set(it) }
        }

        scope.launch {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) {
                    _state.update { it.copy(positionMs = player.currentPosition, bufferedMs = player.bufferedPosition) }
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    override fun prepare(source: PlaybackSource, startPositionMs: Long) {
        currentSource = source
        extraHeaders.set(source.headers)
        val item = MediaItem.Builder()
            .setUri(source.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(source.title).build())
            .apply {
                when (source.format) {
                    StreamFormat.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    StreamFormat.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    else -> Unit
                }
                if (source.subtitles.isNotEmpty()) setSubtitleConfigurations(source.subtitles.map { it.toConfiguration() })
            }.build()
        // file:// and content:// (recordings) are local already; caching them would only copy them
        val factory = if (source.isLive || !source.url.startsWith("http", ignoreCase = true)) liveFactory else vodFactory
        _state.update { it.copy(error = null, status = EngineStatus.BUFFERING, cues = emptyList(), positionMs = 0, durationMs = -1) }
        player.setMediaSource(factory.createMediaSource(item), if (startPositionMs > 0) startPositionMs else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    override fun play() { player.playWhenReady = true; publish() }
    override fun pause() { player.playWhenReady = false; publish() }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
        publish()
    }

    override fun seekTo(positionMs: Long) { player.seekTo(positionMs.coerceAtLeast(0)) }

    override fun seekBy(deltaMs: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        val duration = player.duration
        var target = player.currentPosition + deltaMs
        if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration)
        player.seekTo(target.coerceAtLeast(0))
    }

    override fun restartLive() {
        player.seekToDefaultPosition()
        player.prepare()
    }

    override fun setVolume(volume: Float) { player.volume = volume.coerceIn(0f, 1f) }
    override fun setAudioFocusHandling(enabled: Boolean) { player.setAudioAttributes(audioAttributes, enabled) }
    override fun setVideoEnabled(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
    }

    override fun selectAudio(trackId: String) {
        val (group, index) = findTrack(trackId, C.TRACK_TYPE_AUDIO) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
    }

    override fun selectText(trackId: String?) {
        val params = player.trackSelectionParameters.buildUpon()
        if (trackId == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val (group, index) = findTrack(trackId, C.TRACK_TYPE_TEXT) ?: return
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        }
        player.trackSelectionParameters = params.build()
    }

    override fun addExternalSubtitle(subtitle: ExternalSubtitle) {
        val source = currentSource ?: return
        // Subtitles are part of the MediaItem, so the item is re-prepared at the current position.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        prepare(source.copy(subtitles = source.subtitles + subtitle), player.currentPosition.takeIf { !source.isLive } ?: 0)
    }

    override fun attachVideoView(view: SurfaceView) { player.setVideoSurfaceView(view) }
    override fun detachVideoView(view: SurfaceView) { player.clearVideoSurfaceView(view) }

    override fun release() {
        scope.cancel()
        session?.let { sessionHolderRef?.clear(it); it.release() }
        session = null
        player.release()
    }

    // ------------------------------------------------------------------ internals

    private fun findTrack(id: String, type: Int): Pair<Tracks.Group, Int>? {
        val (g, t) = id.split(':').map { it.toIntOrNull() ?: return null }.let { if (it.size == 2) it[0] to it[1] else return null }
        val group = player.currentTracks.groups.getOrNull(g) ?: return null
        return if (group.type == type && t in 0 until group.length) group to t else null
    }

    private fun trackOptions(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        player.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                out += TrackOption("$gi:$ti", label(f, type, out.size + 1), f.language, g.isTrackSelected(ti))
            }
        }
        return out
    }

    private fun label(f: Format, type: Int, ordinal: Int): String {
        val base = f.label
            ?: f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { Locale.forLanguageTag(it).displayLanguage.takeIf { l -> l.isNotBlank() } }
            ?: "Track $ordinal"
        return if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 2) "$base (${f.channelCount} ch)" else base
    }

    private fun publish() {
        val status = when (player.playbackState) {
            Player.STATE_BUFFERING -> EngineStatus.BUFFERING
            Player.STATE_READY -> EngineStatus.READY
            Player.STATE_ENDED -> EngineStatus.ENDED
            else -> EngineStatus.IDLE
        }
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        _state.update {
            it.copy(
                status = status, playWhenReady = player.playWhenReady, isPlaying = player.isPlaying,
                durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET } ?: -1L,
                isLive = player.isCurrentMediaItemLive, seekable = player.isCurrentMediaItemSeekable,
                videoAspectRatio = ratio,
                audioTracks = trackOptions(C.TRACK_TYPE_AUDIO), textTracks = trackOptions(C.TRACK_TYPE_TEXT),
                textEnabled = C.TRACK_TYPE_TEXT !in player.trackSelectionParameters.disabledTrackTypes
            )
        }
    }

    private fun listener() = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
        override fun onTracksChanged(tracks: Tracks) = publish()
        override fun onVideoSizeChanged(videoSize: VideoSize) = publish()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = publish()
        override fun onCues(cueGroup: CueGroup) { _state.update { it.copy(cues = cueGroup.cues) } }
        override fun onPlayerError(error: PlaybackException) { _state.update { it.copy(error = error.toPlaybackError()) } }
    }

    private fun PlaybackException.toPlaybackError(): PlaybackError {
        val http = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val kind = when (errorCode) {
            in 2000..2999 -> ErrorKind.NETWORK
            in 3000..3999 -> ErrorKind.SOURCE
            in 4000..4999 -> ErrorKind.DECODER
            else -> ErrorKind.OTHER
        }
        val text = when {
            http != null -> "The server returned HTTP $http."
            kind == ErrorKind.NETWORK -> "Can't reach the stream."
            kind == ErrorKind.SOURCE -> "This stream's format isn't supported."
            kind == ErrorKind.DECODER -> "This device can't decode the stream."
            else -> message ?: "Playback failed."
        }
        return PlaybackError(
            code = errorCode, kind = kind, message = text, httpStatus = http,
            behindLiveWindow = errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            unrecognizedFormat = errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || cause is UnrecognizedInputFormatException
        )
    }

    private fun ExternalSubtitle.toConfiguration() =
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(uri))
            .setMimeType(mimeType).setLabel(label).setLanguage(language).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()

    private companion object {
        const val USER_AGENT = "IPTVPlayer/1.0 (ExoPlayer)"
        const val POSITION_POLL_MS = 500L

        /** Software decoders first (for devices whose hardware decoder misbehaves). */
        val softwareFirst = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling).sortedBy { it.hardwareAccelerated }
        }
    }
}
