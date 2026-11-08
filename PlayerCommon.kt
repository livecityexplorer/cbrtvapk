@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.iptv.core.domain.model.AppSettings
import app.iptv.core.domain.model.AspectMode
import app.iptv.core.domain.util.AspectMath
import app.iptv.feature.player.engine.EngineState
import app.iptv.feature.player.engine.PlayerEngine

/** A SurfaceView sized for [mode]; used by the full-screen player and by every multi-view pane. */
@Composable
internal fun VideoSurface(engine: PlayerEngine, state: EngineState, mode: AspectMode) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val (w, h) = AspectMath.surfaceSize(
            with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() }, state.videoAspectRatio, mode
        )
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { engine.attachVideoView(it) } },
            onRelease = { engine.detachVideoView(it) },
            modifier = Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })
        )
    }
}

/** Hides the system bars and keeps the screen on while the screen is shown. */
@Composable
internal fun ImmersiveWhilePlaying() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

/** [onStop]/[onStart] mirror the host's lifecycle (pause/resume playback). */
@Composable
internal fun LifecycleCallbacks(onStop: () -> Unit, onStart: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> onStop()
                Lifecycle.Event.ON_START -> onStart()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Applies the user's subtitle size/colour (Settings > Playback) with a black outline for readability on any picture. */
internal fun SubtitleView.applyUserStyle(s: AppSettings) {
    setStyle(CaptionStyleCompat(s.subtitleColor.argb.toInt(), android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
    setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, s.subtitleSizeSp.toFloat())
}
