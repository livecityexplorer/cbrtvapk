package app.iptv.feature.recordings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.model.Recording
import app.iptv.core.domain.model.RecordingStatus
import androidx.compose.material3.Text as M3Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordingsScreen(onPlay: (Long) -> Unit, viewModel: RecordingsViewModel = hiltViewModel()) {
    val list by viewModel.recordings.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Recording?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { firstFocus.requestFocus() } catch (e: Exception) { } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            viewModel.setFolder(uri.toString())
        }
    }

    // The exact-alarm permission is granted in system settings; re-check when we come back.
    var exactAllowed by remember { mutableStateOf(viewModel.exactAlarmsAllowed()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) exactAllowed = viewModel.exactAlarmsAllowed() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.recordings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.storage_label, folder?.let { Uri.parse(it).lastPathSegment } ?: stringResource(R.string.storage_app)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Button(onClick = {
                try { folderPicker.launch(null) } catch (e: ActivityNotFoundException) { notice = context.getString(R.string.no_folder_picker) }
            }, modifier = Modifier.focusRequester(firstFocus)) { Text(stringResource(R.string.choose_folder)) }
            if (folder != null) Button(onClick = { viewModel.setFolder(null) }) { Text(stringResource(R.string.reset_folder)) }
        }

        if (Build.VERSION.SDK_INT >= 31 && !exactAllowed) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.exact_alarm_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Button(onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
                    } catch (e: Exception) { /* no such settings screen on this device */ }
                }) { Text(stringResource(R.string.exact_alarm_allow)) }
            }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))

        val recordings = list
        when {
            recordings == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            recordings.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.recordings_empty), style = MaterialTheme.typography.bodyLarge)
            }
            else -> {
                val active = recordings.filter { it.status == RecordingStatus.RECORDING }
                val scheduled = recordings.filter { it.status == RecordingStatus.SCHEDULED }.sortedBy { it.startTime }
                val done = recordings.filter { it.status == RecordingStatus.COMPLETED }.sortedByDescending { it.startTime }
                val other = recordings.filter { it.status == RecordingStatus.FAILED || it.status == RecordingStatus.CANCELLED }.sortedByDescending { it.startTime }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    section(R.string.section_recording, active) { selected = it }
                    section(R.string.section_scheduled, scheduled) { selected = it }
                    section(R.string.section_completed, done) { selected = it }
                    section(R.string.section_other, other) { selected = it }
                }
            }
        }
    }

    selected?.let { rec ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { M3Text(rec.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (rec.status == RecordingStatus.COMPLETED && rec.filePath != null) {
                        TextButton(onClick = { selected = null; onPlay(rec.id) }) { M3Text(stringResource(R.string.action_play)) }
                    }
                    if (rec.status == RecordingStatus.RECORDING) {
                        TextButton(onClick = { viewModel.stop(rec.id); selected = null }) { M3Text(stringResource(R.string.action_stop)) }
                    }
                    if (rec.status == RecordingStatus.SCHEDULED) {
                        TextButton(onClick = { viewModel.stop(rec.id); selected = null }) { M3Text(stringResource(R.string.action_cancel)) }
                    }
                    TextButton(onClick = { viewModel.delete(rec.id); selected = null }) { M3Text(stringResource(R.string.action_delete)) }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { M3Text(stringResource(R.string.action_close)) } }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.section(titleRes: Int, entries: List<Recording>, onClick: (Recording) -> Unit) {
    if (entries.isEmpty()) return
    item(key = "h$titleRes") {
        Text(stringResource(titleRes), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    items(entries, key = { it.id }) { RecordingRow(it) { onClick(it) } }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecordingRow(rec: Recording, onClick: () -> Unit) {
    val context = LocalContext.current
    val range = remember(rec.startTime, rec.endTime) {
        DateUtils.formatDateTime(context, rec.startTime, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL) +
            " – " + DateUtils.formatDateTime(context, rec.endTime, DateUtils.FORMAT_SHOW_TIME)
    }
    val size = Formatter.formatShortFileSize(context, rec.sizeBytes)
    val status = when (rec.status) {
        RecordingStatus.RECORDING -> stringResource(R.string.status_recording, size)
        RecordingStatus.SCHEDULED -> stringResource(R.string.status_scheduled)
        RecordingStatus.COMPLETED -> size
        RecordingStatus.FAILED -> stringResource(R.string.status_failed)
        RecordingStatus.CANCELLED -> stringResource(R.string.status_cancelled)
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(rec.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rec.channelName + " · " + range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                status, style = MaterialTheme.typography.bodySmall,
                color = when (rec.status) {
                    RecordingStatus.RECORDING -> androidx.compose.ui.graphics.Color(0xFFFF5252)
                    RecordingStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
