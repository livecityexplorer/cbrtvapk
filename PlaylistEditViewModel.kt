package app.iptv.feature.settings.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.error.IptvError
import app.iptv.core.domain.model.Playlist
import app.iptv.core.domain.model.PlaylistType
import app.iptv.core.domain.repository.PlaylistRepository
import app.iptv.core.domain.sync.ConnectionInfo
import app.iptv.core.domain.sync.PlaylistSyncer
import app.iptv.core.domain.sync.SyncManager
import app.iptv.core.domain.usecase.SavePlaylistUseCase
import app.iptv.core.domain.usecase.SaveResult
import app.iptv.core.domain.validation.PlaylistField
import app.iptv.core.domain.validation.PlaylistValidator
import app.iptv.core.domain.validation.ValidationError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SourceKind { M3U_URL, M3U_FILE, XTREAM, STALKER }

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val info: ConnectionInfo) : TestState
    data class Failed(val error: IptvError) : TestState
}

data class EditUiState(
    val kind: SourceKind = SourceKind.M3U_URL,
    val name: String = "", val url: String = "", val username: String = "", val password: String = "",
    val mac: String = "", val epgUrls: String = "", val fileName: String = "",
    val errors: Map<PlaylistField, ValidationError> = emptyMap(),
    val test: TestState = TestState.Idle,
    val saving: Boolean = false,
    val isNew: Boolean = true
)

@HiltViewModel
class PlaylistEditViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val repository: PlaylistRepository,
    private val savePlaylist: SavePlaylistUseCase,
    private val syncer: PlaylistSyncer,
    private val sync: SyncManager
) : ViewModel() {

    private val editId: Long = handle.get<Long>("id") ?: -1L

    // Form values live in SavedStateHandle so they survive process death.
    private val form: StateFlow<HashMap<String, String>> = handle.getStateFlow(FORM, HashMap<String, String>())
    private val errors = MutableStateFlow<Map<PlaylistField, ValidationError>>(emptyMap())
    private val test = MutableStateFlow<TestState>(TestState.Idle)
    private val saving = MutableStateFlow(false)

    val uiState: StateFlow<EditUiState> = combine(form, errors, test, saving) { f, e, t, s ->
        EditUiState(
            kind = f[K_KIND]?.let { runCatching { SourceKind.valueOf(it) }.getOrNull() } ?: SourceKind.M3U_URL,
            name = f[K_NAME].orEmpty(), url = f[K_URL].orEmpty(), username = f[K_USER].orEmpty(),
            password = f[K_PASS].orEmpty(), mac = f[K_MAC].orEmpty(), epgUrls = f[K_EPG].orEmpty(),
            fileName = f[K_FILE].orEmpty(), errors = e, test = t, saving = s, isNew = editId < 0
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EditUiState())

    init {
        if (editId >= 0 && handle.get<HashMap<String, String>>(FORM).isNullOrEmpty()) {
            viewModelScope.launch { repository.get(editId)?.let { fill(it) } }
        }
    }

    fun onChange(key: String, value: String) {
        setForm(current() + (key to value))
        fieldFor(key)?.let { f -> errors.update { it - f } }
        test.value = TestState.Idle
    }

    fun setKind(kind: SourceKind) {
        val f = current().toMutableMap()
        f[K_KIND] = kind.name
        val isContent = f[K_URL].orEmpty().startsWith("content://")
        if ((kind == SourceKind.M3U_FILE && !isContent) || (kind == SourceKind.M3U_URL && isContent)) {
            f[K_URL] = ""; f[K_FILE] = ""
        }
        setForm(f)
        errors.value = emptyMap(); test.value = TestState.Idle
    }

    fun setFile(uri: String, displayName: String) {
        setForm(current() + (K_URL to uri) + (K_FILE to displayName))
        errors.update { it - PlaylistField.URL }
        test.value = TestState.Idle
    }

    fun onPickerUnavailable() {
        test.value = TestState.Failed(IptvError.InvalidInput("This device has no file picker. Use a playlist URL instead."))
    }

    fun testConnection() {
        viewModelScope.launch {
            val draft = PlaylistValidator.normalize(draft(0))
            val problems = PlaylistValidator.validate(draft) - PlaylistField.NAME // a name isn't needed to test
            if (problems.isNotEmpty()) { errors.value = problems; return@launch }
            test.value = TestState.Running
            test.value = try {
                TestState.Success(syncer.testConnection(draft))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TestState.Failed(e as? IptvError ?: IptvError.Unknown(e))
            }
        }
    }

    fun save(onDone: () -> Unit) {
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            val original = if (editId >= 0) repository.get(editId) else null
            when (val r = savePlaylist(draft(original?.lastRefresh ?: 0))) {
                is SaveResult.Invalid -> { errors.value = r.errors; saving.value = false }
                is SaveResult.Saved -> {
                    // New playlists sync right away; edited ones only when the connection details changed.
                    if (original == null || connectionChanged(original, r.playlist)) sync.sync(r.id)
                    saving.value = false
                    onDone()
                }
            }
        }
    }

    // ---- helpers ----
    private fun connectionChanged(a: Playlist, b: Playlist) =
        a.type != b.type || a.url != b.url || a.username != b.username || a.password != b.password || a.macAddress != b.macAddress

    private fun current(): HashMap<String, String> = handle.get<HashMap<String, String>>(FORM) ?: HashMap()
    private fun setForm(map: Map<String, String>) { handle[FORM] = HashMap(map) }

    private fun fill(p: Playlist) {
        val isFile = p.type == PlaylistType.M3U && p.url.startsWith("content://")
        val kind = when {
            p.type == PlaylistType.XTREAM -> SourceKind.XTREAM
            p.type == PlaylistType.STALKER -> SourceKind.STALKER
            isFile -> SourceKind.M3U_FILE
            else -> SourceKind.M3U_URL
        }
        setForm(
            mapOf(
                K_KIND to kind.name, K_NAME to p.name, K_URL to p.url, K_USER to p.username.orEmpty(),
                K_PASS to p.password.orEmpty(), K_MAC to p.macAddress.orEmpty(),
                K_EPG to p.epgUrls.joinToString("\n"), K_FILE to if (isFile) p.url.substringAfterLast('/') else ""
            )
        )
    }

    private fun draft(lastRefresh: Long): Playlist {
        val f = current()
        val kind = f[K_KIND]?.let { runCatching { SourceKind.valueOf(it) }.getOrNull() } ?: SourceKind.M3U_URL
        return Playlist(
            id = if (editId >= 0) editId else 0,
            name = f[K_NAME].orEmpty(),
            type = when (kind) { SourceKind.XTREAM -> PlaylistType.XTREAM; SourceKind.STALKER -> PlaylistType.STALKER; else -> PlaylistType.M3U },
            url = f[K_URL].orEmpty(),
            username = f[K_USER].takeIf { kind == SourceKind.XTREAM },
            password = f[K_PASS].takeIf { kind == SourceKind.XTREAM },
            macAddress = f[K_MAC].takeIf { kind == SourceKind.STALKER },
            epgUrls = f[K_EPG].orEmpty().lines(),
            lastRefresh = lastRefresh
        )
    }

    private fun fieldFor(key: String): PlaylistField? = when (key) {
        K_NAME -> PlaylistField.NAME; K_URL -> PlaylistField.URL; K_USER -> PlaylistField.USERNAME
        K_PASS -> PlaylistField.PASSWORD; K_MAC -> PlaylistField.MAC; K_EPG -> PlaylistField.EPG
        else -> null
    }

    companion object {
        const val FORM = "form"
        const val K_KIND = "kind"; const val K_NAME = "name"; const val K_URL = "url"; const val K_USER = "user"
        const val K_PASS = "pass"; const val K_MAC = "mac"; const val K_EPG = "epg"; const val K_FILE = "file"
    }
}
