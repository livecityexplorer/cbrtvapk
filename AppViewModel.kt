package app.iptv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.AppSettings
import app.iptv.core.domain.model.RecordingStatus
import app.iptv.core.domain.repository.PreferencesRepository
import app.iptv.core.domain.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    recordings: RecordingRepository,
    private val prefs: PreferencesRepository
) : ViewModel() {
    /** Drives the global "REC" indicator: true while any recording is being written. */
    val recordingActive: StateFlow<Boolean> = recordings.observeAll()
        .map { list -> list.any { it.status == RecordingStatus.RECORDING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** null until DataStore has delivered the first value (the UI shows a blank screen meanwhile, no theme/disclaimer flash). */
    val settings: StateFlow<AppSettings?> = prefs.settings.map<AppSettings, AppSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun acceptDisclaimer() { viewModelScope.launch { prefs.updateSettings { it.copy(disclaimerAccepted = true) } } }
}
