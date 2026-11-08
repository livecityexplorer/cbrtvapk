@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv.feature.player

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.SubtitleView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.feature.player.engine.EngineState
import app.iptv.feature.player.engine.EngineStatus

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VodPlayerScreen(onBack: () -> Unit, viewModel: VodPlayerViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val inPip by viewModel.inPip.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rootFocus = remember { FocusRequester() }
    val failed = ui.status is PlayerStatus.Failed

    ImmersiveWhilePlaying()
    LifecycleCallbacks(onStop = viewModel::onBackgrounded, onStart = viewModel::onForegrounded)
    LaunchedEffect(ui.finished) { if (ui.finished) onBack() }
    BackHandler(enabled = ui.menu != null || ui.controls) {
        if (ui.menu != null) viewModel.closeMenu() else viewModel.hideControls()
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.addSubtitle(uri.toString(), displayName(context, uri))
        }
        viewModel.closeMenu()
    }

    Box(
        Modifier
            .fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus).focusable()
            .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.showControls() }) }
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && ui.controls) viewModel.pokeControls(); false }
            .onKeyEvent { e -> !inPip && e.type == KeyEventType.KeyDown && handleVodKey(e.key, ui, viewModel) }
    ) {
        VideoSurface(viewModel.engine, engine, ui.aspect)
        AndroidView(
            factory = { ctx -> SubtitleView(ctx).apply { applyUserStyle(viewModel.settings) } },
            update = { it.setCues(engine.cues) },
            modifier = Modifier.fillMaxSize()
        )

        VodStatus(ui, engine, onRetry = viewModel::retry, onBack = onBack)

        AnimatedVisibility(
            visible = ui.controls && !failed && !inPip,
            enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            VodControls(
                ui, engine,
                onToggle = viewModel::togglePlayPause, onSeek = viewModel::seek, onFraction = viewModel::seekToFraction,
                onAudio = { viewModel.openMenu(PlayerMenu.AUDIO) }, onSubtitles = { viewModel.openMenu(PlayerMenu.SUBTITLES) },
                onAspect = { viewModel.openMenu(PlayerMenu.ASPECT) }, onNext = viewModel::playNext
            )
        }

        ui.seekFeedback?.takeIf { !inPip }?.let {
            Text(
                it, Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(16.dp),
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }

    when (ui.menu) {
        PlayerMenu.AUDIO -> TrackDialog(R.string.menu_audio, engine.audioTracks, extra = null, offSelected = null, onOff = null,
            onSelect = { viewModel.selectAudio(it.id); viewModel.closeMenu() }, onDismiss = viewModel::closeMenu)
        PlayerMenu.SUBTITLES -> TrackDialog(R.string.menu_subtitles, engine.textTracks, extra = { subtitlePicker.launchSafely() },
            offSelected = !engine.textEnabled || engine.textTracks.none { it.selected },
            onOff = { viewModel.selectSubtitle(null); viewModel.closeMenu() },
            onSelect = { viewModel.selectSubtitle(it.id); viewModel.closeMenu() }, onDismiss = viewModel::closeMenu)
        PlayerMenu.ASPECT -> AspectDialog(ui.aspect, onSelect = { viewModel.setAspect(it); viewModel.closeMenu() }, onDismiss = viewModel::closeMenu)
        null -> Unit
    }

    // Without controls or a dialog, the root owns the D-pad again (Left/Right seek).
    LaunchedEffect(ui.controls, ui.menu, failed) {
        if (!ui.controls && ui.menu == null && !failed) try { rootFocus.requestFocus() } catch (e: Exception) { }
    }
}

/** Left/Right = seek (hold to speed up), OK/Up/Down = controls; media keys work everywhere. */
private fun handleVodKey(key: Key, ui: VodPlayerUi, vm: VodPlayerViewModel): Boolean {
    when (key) {
        Key.MediaPlayPause -> { vm.togglePlayPause(); return true }
        Key.MediaPlay -> { vm.resume(); return true }
        Key.MediaPause -> { vm.pausePlayback(); return true }
        Key.MediaFastForward -> { vm.seek(true); return true }
        Key.MediaRewind -> { vm.seek(false); return true }
        Key.MediaNext -> { vm.playNext(); return true }
    }
    if (ui.controls || ui.menu != null || ui.status is PlayerStatus.Failed) return false // focus navigation / seek bar handles it
    return when (key) {
        Key.DirectionLeft -> { vm.seek(false); true }
        Key.DirectionRight -> { vm.seek(true); true }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp, Key.DirectionDown -> { vm.showControls(); true }
        else -> false
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VodStatus(ui: VodPlayerUi, engine: EngineState, onRetry: () -> Unit, onBack: () -> Unit) {
    when (val s = ui.status) {
        is PlayerStatus.Failed -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) { } }
            Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.title, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.playback_failed), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                Text(s.message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.retry)) }
                    Button(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
            }
        }
        else -> {
            val busy = s is PlayerStatus.Loading || s is PlayerStatus.Reconnecting || engine.status == EngineStatus.BUFFERING
            if (busy) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                if (s is PlayerStatus.Reconnecting) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.reconnecting, s.attempt, s.max), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VodControls(
    ui: VodPlayerUi, engine: EngineState,
    onToggle: () -> Unit, onSeek: (Boolean) -> Unit, onFraction: (Float) -> Unit,
    onAudio: () -> Unit, onSubtitles: () -> Unit, onAspect: () -> Unit, onNext: () -> Unit
) {
    val seekFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { seekFocus.requestFocus() } catch (e: Exception) { } } // start on the seek bar: Left/Right seek right away

    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))).padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(ui.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        ui.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1) }

        val duration = engine.durationMs
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(formatTime(engine.positionMs), style = MaterialTheme.typography.bodyMedium)
            SeekBar(Modifier.weight(1f), engine.positionMs, duration, seekFocus, onSeek, onToggle, onFraction)
            Text(if (duration > 0) formatTime(duration) else "--:--", style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onToggle) { Text(stringResource(if (engine.playWhenReady) R.string.ctl_pause else R.string.ctl_play)) }
            Button(onClick = onAudio, enabled = engine.audioTracks.isNotEmpty()) { Text(stringResource(R.string.ctl_audio)) }
            Button(onClick = onSubtitles) { Text(stringResource(R.string.ctl_subtitles)) }
            Button(onClick = onAspect) { Text(stringResource(R.string.ctl_aspect)) }
            if (ui.hasNext) Button(onClick = onNext) { Text(stringResource(R.string.vod_next_episode)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeekBar(
    modifier: Modifier, position: Long, duration: Long, focus: FocusRequester,
    onSeek: (Boolean) -> Unit, onToggle: () -> Unit, onFraction: (Float) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier.height(28.dp)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                    Key.DirectionLeft -> { onSeek(false); true }
                    Key.DirectionRight -> { onSeek(true); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onToggle(); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { off -> onFraction(off.x / size.width) } },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(if (focused) 10.dp else 5.dp).background(Color(0x44FFFFFF), RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)))
        }
    }
}
