package app.iptv.player

import android.app.Application
import app.iptv.core.data.work.RefreshScheduler
import app.iptv.core.domain.repository.PreferencesRepository
import app.iptv.core.domain.repository.RecordingController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class IptvApp : Application() {
    @Inject lateinit var recordingController: RecordingController
    @Inject lateinit var prefs: PreferencesRepository
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Background refresh of playlists + guide follows Settings > Program guide (auto-refresh toggle + interval).
        appScope.launch {
            prefs.settings.map { it.epgAutoRefresh to it.epgIntervalHours }.distinctUntilChanged().collect { (auto, hours) ->
                if (auto) RefreshScheduler.schedule(this@IptvApp, intervalHours = hours.toLong()) else RefreshScheduler.cancel(this@IptvApp)
            }
        }
        // Re-arm recording alarms (they are lost on reboot / app update) and close recordings the OS killed.
        appScope.launch { recordingController.rescheduleAll() }
    }
}
