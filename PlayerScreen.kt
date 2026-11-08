@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv.feature.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.model.AspectMode
import app.iptv.core.domain.model.Channel
import app.iptv.core.domain.model.EpgProgram
import app.iptv.feature.player.engine.EngineState
import app.iptv.feature.player.engine.EngineStatus
import app.iptv.feature.player.engine.TrackOption
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import androidx.compose.material3.Text as M3Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, onOpenMultiView: (Long) -> Unit = {}, viewModel: PlayerViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val inPip by viewModel.inPip.collectAsStateWithLifecycle()
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val now by rememberNow()
    val context = LocalContext.current
    val rootFocus = remember { FocusRequester() }
    val failed = ui.status is PlayerStatus.Failed

    ImmersiveWhilePlaying()
    LifecycleCallbacks(onStop = viewModel::onBackgrounded, onStart = viewModel::onForegrounded)

    BackHandler(enabled = ui.menu != null || ui.overlay != OverlayMode.HIDDEN) {
        if (ui.menu != null) viewModel.closeMenu() else viewModel.hideOverlay()
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
            // any key press keeps the controls on screen a bit longer (not consumed)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && ui.overlay == OverlayMode.CONTROLS) viewModel.pokeOverlay()
                false
            }
            .onKeyEvent { e -> !inPip && e.type == KeyEventType.KeyDown && handleKey(e.key, ui, viewModel) }
    ) {
        VideoSurface(viewModel.engine, engine, ui.aspect)

        AndroidView(
            factory = { ctx -> SubtitleView(ctx).apply { applyUserStyle(viewModel.settings) } },
            update = { it.setCues(engine.cues) },
            modifier = Modifier.fillMaxSize()
        )

        StatusLayer(ui, engine, onRetry = viewModel::retry, onBack = onBack)

        AnimatedVisibility(
            visible = ui.overlay != OverlayMode.HIDDEN && !failed && !inPip,
            enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            InfoPanel(
                ui = ui, engine = engine, now = now,
                onPlayPause = viewModel::togglePlayPause,
                onAudio = { viewModel.openMenu(PlayerMenu.AUDIO) },
                onSubtitles = { viewModel.openMenu(PlayerMenu.SUBTITLES) },
                onAspect = { viewModel.openMenu(PlayerMenu.ASPECT) },
                onMultiView = { ui.channel?.let { onOpenMultiView(it.id) } },
                onRecord = viewModel::toggleRecord
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
        PlayerMenu.AUDIO -> TrackDialog(R.string.menu_audio, engine.audioTracks, extra = null,
            offSelected = null, onOff = null, onSelect = { viewModel.selectAudio(it.id); viewModel.closeMenu() }, onDismiss = viewModel::closeMenu)
        PlayerMenu.SUBTITLES -> TrackDialog(R.string.menu_subtitles, engine.textTracks,
            extra = { subtitlePicker.launchSafely() },
            offSelected = !engine.textEnabled || engine.textTracks.none { it.selected },
            onOff = { viewModel.selectSubtitle(null); viewModel.closeMenu() },
            onSelect = { viewModel.selectSubtitle(it.id); viewModel.closeMenu() }, onDismiss = viewModel::closeMenu)
        PlayerMenu.ASPECT -> AspectDialog(ui.aspect, onSelect = { viewModel.setAspect(it); viewModel.closeMenu() }, onDismiss = viewModel::closeMenu)
        null -> Unit
    }

    // Hand focus back to the root whenever no focusable controls/dialog are on screen, so the D-pad zaps again.
    LaunchedEffect(ui.overlay, ui.menu, failed) {
        if (ui.overlay != OverlayMode.CONTROLS && ui.menu == null && !failed) {
            try { rootFocus.requestFocus() } catch (e: Exception) { /* not attached yet */ }
        }
    }
}

/**
 * D-pad: Up/Down = previous/next channel, Left/Right = seek (when seekable), OK = controls.
 * Remote keys: CH+/CH- zap, rewind/fast-forward seek, play/pause toggles.
 */
private fun handleKey(key: Key, ui: PlayerUiState, vm: PlayerViewModel): Boolean {
    when (key) {
        Key.MediaPlayPause -> { vm.togglePlayPause(); return true }
        Key.MediaPlay -> { vm.resume(); return true }
        Key.MediaPause -> { vm.pausePlayback(); return true }
        Key.ChannelUp, Key.PageUp, Key.MediaNext -> { vm.zap(next = true); return true }
        Key.ChannelDown, Key.PageDown, Key.MediaPrevious -> { vm.zap(next = false); return true }
        Key.MediaFastForward -> { vm.seek(true); return true }
        Key.MediaRewind -> { vm.seek(false); return true }
    }
    // With the controls, a dialog or the error screen showing, the D-pad belongs to Compose focus navigation.
    if (ui.overlay == OverlayMode.CONTROLS || ui.menu != null || ui.status is PlayerStatus.Failed) return false
    return when (key) {
        Key.DirectionUp -> { vm.zap(next = false); true }
        Key.DirectionDown -> { vm.zap(next = true); true }
        Key.DirectionLeft -> { vm.seek(false); true }
        Key.DirectionRight -> { vm.seek(true); true }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { vm.showControls(); true }
        else -> false
    }
}

// ---------------------------------------------------------------- video

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusLayer(ui: PlayerUiState, engine: EngineState, onRetry: () -> Unit, onBack: () -> Unit) {
    when (val s = ui.status) {
        is PlayerStatus.Failed -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) { } }
            Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.channel?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
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

// ---------------------------------------------------------------- banner + controls

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoPanel(
    ui: PlayerUiState, engine: EngineState, now: Long,
    onPlayPause: () -> Unit, onAudio: () -> Unit, onSubtitles: () -> Unit, onAspect: () -> Unit, onMultiView: () -> Unit, onRecord: () -> Unit
) {
    val channel = ui.channel
    val current = ui.nowNext.firstOrNull { it.isLiveAt(now) }
    val next = ui.nowNext.firstOrNull { it.startTime >= (current?.endTime ?: now) }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val controls = ui.overlay == OverlayMode.CONTROLS
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(controls) { if (controls) try { playFocus.requestFocus() } catch (e: Exception) { } }

    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (channel != null) ChannelLogo(channel)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (channel != null && channel.number > 0) Text(channel.number.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text(channel?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ui.catchupStart != null) Badge(stringResource(R.string.catchup_badge))
                    else if (engine.isLive) Badge(stringResource(R.string.live_badge))
                    if (ui.recording) Badge("● REC")
                }
                if (current != null) {
                    Text(
                        stringResource(R.string.now_label) + ": " + current.title + "  (" + timeFmt.format(Date(current.startTime)) + " – " + timeFmt.format(Date(current.endTime)) + ")",
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(4.dp).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(current.progressAt(now)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                }
                if (next != null) {
                    Text(
                        stringResource(R.string.next_label) + ": " + next.title + "  (" + timeFmt.format(Date(next.startTime)) + ")",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (engine.seekable && engine.durationMs > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(formatTime(engine.positionMs), style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f).height(4.dp).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth((engine.positionMs.toFloat() / engine.durationMs).coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
                Text(formatTime(engine.durationMs), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (controls) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlayPause, modifier = Modifier.focusRequester(playFocus)) {
                    Text(stringResource(if (engine.playWhenReady) R.string.ctl_pause else R.string.ctl_play))
                }
                Button(onClick = onAudio, enabled = engine.audioTracks.isNotEmpty()) { Text(stringResource(R.string.ctl_audio)) }
                Button(onClick = onSubtitles) { Text(stringResource(R.string.ctl_subtitles)) }
                Button(onClick = onAspect) { Text(stringResource(R.string.ctl_aspect)) }
                Button(onClick = onMultiView) { Text(stringResource(R.string.ctl_multiview)) }
                Button(onClick = onRecord) { Text(stringResource(if (ui.recording) R.string.ctl_stop_rec else R.string.ctl_record)) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun Badge(text: String) {
    Text(
        text, Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = Color.White
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelLogo(channel: Channel) {
    val initials = remember(channel.name) {
        channel.name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { "?" }
    }
    Box(Modifier.size(width = 96.dp, height = 60.dp).background(Color(0x33FFFFFF), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (channel.logoUrl.isNullOrBlank()) Text(initials, style = MaterialTheme.typography.titleLarge)
        else SubcomposeAsyncImage(
            model = channel.logoUrl, contentDescription = channel.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(initials) } },
            error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(initials) } }
        )
    }
}

// ---------------------------------------------------------------- menus

@Composable
internal fun TrackDialog(
    @StringRes title: Int, tracks: List<TrackOption>, extra: (() -> Unit)?,
    offSelected: Boolean?, onOff: (() -> Unit)?, onSelect: (TrackOption) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (onOff != null) TextButton(onClick = onOff) { M3Text(check(offSelected == true) + stringResource(R.string.subs_off)) }
                tracks.forEach { t -> TextButton(onClick = { onSelect(t) }) { M3Text(check(t.selected) + t.label) } }
                if (tracks.isEmpty() && onOff == null) M3Text(stringResource(R.string.menu_none))
                if (extra != null) TextButton(onClick = extra) { M3Text(stringResource(R.string.subs_add_file)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { M3Text(stringResource(R.string.menu_close)) } }
    )
}

@Composable
internal fun AspectDialog(current: AspectMode, onSelect: (AspectMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text(stringResource(R.string.menu_aspect)) },
        text = {
            Column {
                AspectMode.entries.forEach { m ->
                    TextButton(onClick = { onSelect(m) }) { M3Text(check(m == current) + stringResource(aspectLabel(m))) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { M3Text(stringResource(R.string.menu_close)) } }
    )
}

private fun check(selected: Boolean) = if (selected) "✓ " else "    "

@StringRes
private fun aspectLabel(m: AspectMode): Int = when (m) {
    AspectMode.FIT -> R.string.aspect_fit
    AspectMode.ZOOM -> R.string.aspect_zoom
    AspectMode.STRETCH -> R.string.aspect_stretch
    AspectMode.RATIO_16_9 -> R.string.aspect_16_9
    AspectMode.RATIO_4_3 -> R.string.aspect_4_3
}

// ---------------------------------------------------------------- window / lifecycle helpers

internal fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

internal fun androidx.activity.result.ActivityResultLauncher<Array<String>>.launchSafely() {
    try { launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { /* no file picker on this device */ }
}

internal fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) { delay(30_000); value = System.currentTimeMillis() }
}
