package app.iptv.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.AppSettings
import app.iptv.core.domain.repository.*
import app.iptv.core.domain.sync.EpgSyncer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A one-shot message for a snackbar-style line; the screen resolves [resId] with [args]. */
data class UiMessage(val resId: Int, val args: List<Any> = emptyList(), val id: Long = System.nanoTime())

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val gate: ParentalGate,
    private val backup: BackupRepository,
    private val playlists: PlaylistRepository,
    private val epgSyncer: EpgSyncer,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val lastEpgRefresh: StateFlow<Long> = prefs.lastEpgRefresh.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) { viewModelScope.launch { prefs.updateSettings(transform) } }
    fun notify(resId: Int) { _message.value = UiMessage(resId) }
    fun consumeMessage() { _message.value = null }

    // ---------------------------------------------------------------- parental
    suspend fun isLockedNow() = gate.isLockedNow()
    suspend fun verifyPin(pin: String) = gate.verify(pin)
    suspend fun setPin(newPin: String, currentPin: String?): Boolean =
        gate.setPin(newPin, currentPin).also { if (it) _message.value = UiMessage(R.string.msg_pin_saved) }
    suspend fun clearPin(currentPin: String): Boolean =
        gate.clearPin(currentPin).also { if (it) _message.value = UiMessage(R.string.msg_pin_removed) }
    fun setHideAdult(enabled: Boolean) { viewModelScope.launch { gate.setHideAdult(enabled) } }
    fun lockNow() { gate.lockAll(); _message.value = UiMessage(R.string.msg_locked) }

    // ---------------------------------------------------------------- EPG
    fun refreshGuideNow() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            var ok = 0; var failed = 0
            try {
                playlists.observeAll().first().forEach { p ->
                    try { epgSyncer.refresh(p.id); ok++ }
                    catch (e: CancellationException) { throw e }
                    catch (e: Throwable) { failed++ }
                }
                if (ok > 0) prefs.setLastEpgRefresh(System.currentTimeMillis())
                _message.value = when {
                    ok == 0 && failed == 0 -> UiMessage(R.string.msg_no_playlists)
                    failed == 0 -> UiMessage(R.string.msg_guide_ok)
                    else -> UiMessage(R.string.msg_guide_partial, listOf(failed))
                }
            } finally { _busy.value = false }
        }
    }

    // ---------------------------------------------------------------- backup / restore (SAF)
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = try {
                val json = backup.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw java.io.IOException("Cannot open file")
                }
                UiMessage(R.string.msg_export_ok)
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { UiMessage(R.string.msg_export_failed) }
            finally { _busy.value = false }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        val bytes = s.readBytes(); require(bytes.size <= MAX_BACKUP_BYTES) { "too big" }; bytes.toString(Charsets.UTF_8)
                    } ?: throw java.io.IOException("Cannot open file")
                }
                val r = backup.import(text)
                UiMessage(R.string.msg_import_ok, listOf(r.playlistsAdded, r.playlistsSkipped))
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { UiMessage(R.string.msg_import_invalid) }
            catch (e: Throwable) { UiMessage(R.string.msg_import_failed) }
            finally { _busy.value = false }
        }
    }

    private companion object { const val MAX_BACKUP_BYTES = 5 * 1024 * 1024 }
}
