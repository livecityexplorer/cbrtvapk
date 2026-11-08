package app.iptv.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.iptv.core.domain.reminder.ReminderRequest
import app.iptv.feature.player.PipState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.iptv.core.domain.model.ThemeMode
import app.iptv.player.ui.theme.IptvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var pipState: PipState

    private val pendingChannel = mutableStateOf<Long?>(null)
    private var pipReceiverRegistered = false
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_TOGGLE) pipState.onTogglePlayPause?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readIntent(intent)
        setContent {
            val appVm: AppViewModel = hiltViewModel()
            val settings by appVm.settings.collectAsStateWithLifecycle()
            IptvTheme(amoled = settings?.theme == ThemeMode.AMOLED) {
                AppRoot(settings, onAccept = appVm::acceptDisclaimer, pendingChannelId = pendingChannel.value, onPendingConsumed = { pendingChannel.value = null })
            }
        }
        // Keep the PiP window's aspect ratio / play-pause button in sync; on Android 12+ also enables auto-enter
        // so the home gesture opens PiP without waiting for onUserLeaveHint.
        if (Build.VERSION.SDK_INT >= 26 && supportsPip()) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    pipState.params.collect { p -> try { setPictureInPictureParams(buildPipParams(p)) } catch (e: IllegalStateException) { } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    /** Home button while a channel is playing -> Picture-in-Picture (Android 8+). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && supportsPip() && !isInPictureInPictureMode && pipState.params.value.eligible) {
            try { enterPictureInPictureMode(buildPipParams(pipState.params.value)) } catch (e: IllegalStateException) { }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipState.setInPip(isInPictureInPictureMode)
        if (isInPictureInPictureMode && !pipReceiverRegistered) {
            ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
            pipReceiverRegistered = true
        } else if (!isInPictureInPictureMode && pipReceiverRegistered) {
            unregisterReceiver(pipReceiver)
            pipReceiverRegistered = false
        }
    }

    override fun onStop() {
        // Stopped while in PiP = the user dismissed the PiP window: let the player pause instead of playing on.
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) pipState.setInPip(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (pipReceiverRegistered) { unregisterReceiver(pipReceiver); pipReceiverRegistered = false }
        super.onDestroy()
    }

    private fun supportsPip() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun buildPipParams(p: PipState.Params): PictureInPictureParams {
        // Android only accepts ratios between roughly 1:2.39 and 2.39:1.
        val ratio = (p.videoAspect.takeIf { it > 0f } ?: (16f / 9f)).coerceIn(0.5f, 2.3f)
        val toggle = RemoteAction(
            Icon.createWithResource(this, if (p.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            getString(R.string.pip_play_pause), getString(R.string.pip_play_pause),
            PendingIntent.getBroadcast(this, 0, Intent(ACTION_PIP_TOGGLE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
            .setActions(listOf(toggle))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(p.eligible)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    /** Reminder notifications carry the channel to open. */
    private fun readIntent(intent: Intent?) {
        val id = intent?.getLongExtra(ReminderRequest.EXTRA_CHANNEL_ID, -1L) ?: -1L
        if (id >= 0) pendingChannel.value = id
    }

    private companion object { const val ACTION_PIP_TOGGLE = "app.iptv.player.PIP_TOGGLE" }
}
