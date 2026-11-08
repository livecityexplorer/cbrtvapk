@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv.feature.player.engine

import androidx.media3.session.MediaSession
import javax.inject.Inject
import javax.inject.Singleton

/** The main player's MediaSession; PlaybackService hands it to the system (notification, media keys, Assistant). */
@Singleton
class PlayerSessionHolder @Inject constructor() {
    @Volatile var session: MediaSession? = null
        private set

    fun set(s: MediaSession) { session = s }
    fun clear(s: MediaSession) { if (session === s) session = null }
}
