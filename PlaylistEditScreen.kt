package app.iptv.feature.settings.playlists

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.validation.PlaylistField
import app.iptv.core.domain.validation.ValidationError
import app.iptv.feature.settings.R
import app.iptv.feature.settings.playlists.PlaylistEditViewModel.Companion.K_EPG
import app.iptv.feature.settings.playlists.PlaylistEditViewModel.Companion.K_MAC
import app.iptv.feature.settings.playlists.PlaylistEditViewModel.Companion.K_NAME
import app.iptv.feature.settings.playlists.PlaylistEditViewModel.Companion.K_PASS
import app.iptv.feature.settings.playlists.PlaylistEditViewModel.Companion.K_URL
import app.iptv.feature.settings.playlists.PlaylistEditViewModel.Companion.K_USER
import java.text.DateFormat
import java.util.Date
import androidx.compose.material3.Text as M3Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistEditScreen(onClose: () -> Unit, viewModel: PlaylistEditViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.setFile(uri.toString(), displayName(context, uri))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(if (ui.isNew) R.string.add_playlist else R.string.edit_playlist),
            style = MaterialTheme.typography.headlineMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceKind.entries.forEach { k ->
                FilterChip(selected = ui.kind == k, onClick = { viewModel.setKind(k) }, label = { M3Text(kindLabel(k)) })
            }
        }

        Field(R.string.field_name, ui.name, { viewModel.onChange(K_NAME, it) }, ui.errors[PlaylistField.NAME])

        when (ui.kind) {
            SourceKind.M3U_URL -> Field(R.string.field_playlist_url, ui.url, { viewModel.onChange(K_URL, it) }, ui.errors[PlaylistField.URL], uri = true)
            SourceKind.M3U_FILE -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = {
                        try { picker.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { viewModel.onPickerUnavailable() }
                    }) { Text(stringResource(R.string.choose_file)) }
                    Text(ui.fileName.ifEmpty { stringResource(R.string.no_file) }, style = MaterialTheme.typography.bodyLarge)
                }
                ui.errors[PlaylistField.URL]?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            }
            SourceKind.XTREAM -> {
                Field(R.string.field_server_url, ui.url, { viewModel.onChange(K_URL, it) }, ui.errors[PlaylistField.URL], uri = true)
                Field(R.string.field_username, ui.username, { viewModel.onChange(K_USER, it) }, ui.errors[PlaylistField.USERNAME])
                Field(R.string.field_password, ui.password, { viewModel.onChange(K_PASS, it) }, ui.errors[PlaylistField.PASSWORD], password = true)
            }
            SourceKind.STALKER -> {
                Field(R.string.field_portal_url, ui.url, { viewModel.onChange(K_URL, it) }, ui.errors[PlaylistField.URL], uri = true)
                Field(R.string.field_mac, ui.mac, { viewModel.onChange(K_MAC, it) }, ui.errors[PlaylistField.MAC])
            }
        }

        Field(R.string.field_epg, ui.epgUrls, { viewModel.onChange(K_EPG, it) }, ui.errors[PlaylistField.EPG], uri = true, singleLine = false)

        TestResult(ui.test)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::testConnection, enabled = ui.test !is TestState.Running && !ui.saving) {
                Text(stringResource(R.string.test_connection))
            }
            Button(onClick = { viewModel.save(onClose) }, enabled = !ui.saving) { Text(stringResource(R.string.save)) }
            Button(onClick = onClose) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun Field(
    labelRes: Int, value: String, onChange: (String) -> Unit, error: ValidationError?,
    uri: Boolean = false, password: Boolean = false, singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { M3Text(stringResource(labelRes)) },
        isError = error != null,
        supportingText = { error?.let { M3Text(errorText(it)) } },
        singleLine = singleLine, minLines = if (singleLine) 1 else 2,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else if (uri) KeyboardType.Uri else KeyboardType.Text,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default
        )
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TestResult(state: TestState) {
    when (state) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.test_running))
        }
        is TestState.Success -> Column {
            Text(stringResource(R.string.test_ok), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            state.info.status?.let { Text(stringResource(R.string.test_status, it)) }
            state.info.expiresAt?.let { Text(stringResource(R.string.test_expires, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)))) }
            state.info.maxConnections?.let { Text(stringResource(R.string.test_connections, it)) }
        }
        is TestState.Failed -> Text(state.error.message ?: "", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun errorText(e: ValidationError): String = stringResource(
    when (e) {
        ValidationError.REQUIRED -> R.string.error_required
        ValidationError.INVALID_URL -> R.string.error_invalid_url
        ValidationError.INVALID_MAC -> R.string.error_invalid_mac
    }
)

@Composable
private fun kindLabel(k: SourceKind): String = stringResource(
    when (k) {
        SourceKind.M3U_URL -> R.string.kind_m3u_url
        SourceKind.M3U_FILE -> R.string.kind_m3u_file
        SourceKind.XTREAM -> R.string.kind_xtream
        SourceKind.STALKER -> R.string.kind_stalker
    }
)

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
