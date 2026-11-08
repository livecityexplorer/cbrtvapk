package app.iptv.feature.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.Recording
import app.iptv.core.domain.repository.PreferencesRepository
import app.iptv.core.domain.repository.RecordingController
import app.iptv.core.domain.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    repository: RecordingRepository,
    private val controller: RecordingController,
    private val prefs: PreferencesRepository
) : ViewModel() {
    /** null = loading */
    val recordings: StateFlow<List<Recording>?> = repository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val folder: StateFlow<String?> = prefs.recordingsFolder.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun stop(id: Long) { viewModelScope.launch { controller.stop(id) } }
    fun delete(id: Long) { viewModelScope.launch { controller.delete(id) } }
    fun setFolder(uri: String?) { viewModelScope.launch { prefs.setRecordingsFolder(uri) } }
    fun exactAlarmsAllowed(): Boolean = controller.exactAlarmsAllowed()
}
