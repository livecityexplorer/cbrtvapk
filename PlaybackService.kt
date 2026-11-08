@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv.feature.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import app.iptv.feature.player.engine.PlayerSessionHolder
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Hosts the main player's session so playback + the media notification survive the app going to the background. */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var holder: PlayerSessionHolder

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = holder.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away while paused: nothing left to keep alive.
        val player = holder.session?.player
        if (player == null || !player.playWhenReady) stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}

/** Connecting a controller binds [PlaybackService]; Media3 then manages notification/foreground state itself. */
class BackgroundAudio private constructor(private val future: ListenableFuture<MediaController>) {
    fun release() { MediaController.releaseFuture(future) }

    companion object {
        fun connect(context: Context): BackgroundAudio {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            return BackgroundAudio(MediaController.Builder(context, token).buildAsync())
        }
    }
}
