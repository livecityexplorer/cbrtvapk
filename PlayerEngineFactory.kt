@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv.feature.player.engine

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.iptv.core.domain.model.PlaybackConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    private val cache: Cache,
    private val sessionHolder: PlayerSessionHolder
) {
    /** [withSession] = this is the main player (gets the MediaSession); multi-view panes pass false and [handleAudioFocus] = false. */
    fun create(config: PlaybackConfig = PlaybackConfig(), handleAudioFocus: Boolean = true, withSession: Boolean = false): PlayerEngine =
        ExoPlayerEngine(context, okHttp, cache, config, handleAudioFocus, if (withSession) sessionHolder else null)
}

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {
    /** 64 MB on-disk cache for VOD only (movies/episodes seek and resume faster). One SimpleCache per folder. */
    @Provides @Singleton
    fun mediaCache(@ApplicationContext context: Context): Cache =
        SimpleCache(File(context.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024), StandaloneDatabaseProvider(context))
}
