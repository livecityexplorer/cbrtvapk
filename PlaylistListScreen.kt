package app.iptv.feature.settings.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.model.Playlist
import app.iptv.core.domain.model.PlaylistType
import app.iptv.core.domain.sync.SyncStage
import app.iptv.core.domain.sync.SyncState
import app.iptv.feature.settings.R
import androidx.compose.material3.Text as M3Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: PlaylistListViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val states by viewModel.syncStates.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }
    val addFocus = remember { FocusRequester() }
    val list = playlists
    LaunchedEffect(list != null) { if (list != null) runCatching { addFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.playlists_title), style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onAdd, modifier = Modifier.focusRequester(addFocus)) { Text(stringResource(R.string.add_playlist)) }
        }
        Spacer(Modifier.height(24.dp))
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.playlists_empty), style = MaterialTheme.typography.bodyLarge)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { p ->
                    PlaylistRow(
                        playlist = p, state = states[p.id],
                        onRefresh = { viewModel.refresh(p.id) }, onEdit = { onEdit(p.id) }, onDelete = { pendingDelete = p }
                    )
                }
            }
        }
    }

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { M3Text(stringResource(R.string.delete_title)) },
            text = { M3Text(stringResource(R.string.delete_message, p.name)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(p.id); pendingDelete = null }) { M3Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { M3Text(stringResource(R.string.cancel)) } }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistRow(playlist: Playlist, state: SyncState?, onRefresh: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val (status, isError) = statusText(playlist, state)
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                typeLabel(playlist) + "  ·  " + status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh, enabled = state !is SyncState.Running) { Text(stringResource(R.string.refresh)) }
            Button(onClick = onEdit) { Text(stringResource(R.string.edit)) }
            Button(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun typeLabel(p: Playlist): String = when {
    p.type == PlaylistType.M3U && p.url.startsWith("content://") -> stringResource(R.string.kind_m3u_file)
    p.type == PlaylistType.M3U -> stringResource(R.string.type_m3u)
    p.type == PlaylistType.XTREAM -> stringResource(R.string.type_xtream)
    else -> stringResource(R.string.type_stalker)
}

/** Returns the status line and whether it is an error. */
@Composable
private fun statusText(p: Playlist, state: SyncState?): Pair<String, Boolean> = when (state) {
    is SyncState.Running -> (when (state.stage) {
        SyncStage.CONNECTING -> stringResource(R.string.stage_connecting)
        SyncStage.LIVE -> stringResource(R.string.stage_live, state.count)
        SyncStage.MOVIES -> stringResource(R.string.stage_movies)
        SyncStage.SERIES -> stringResource(R.string.stage_series)
        SyncStage.SAVING -> stringResource(R.string.stage_saving, state.count)
    }) to false
    is SyncState.Success -> stringResource(R.string.sync_done, state.summary.channels, state.summary.movies, state.summary.series) to false
    is SyncState.Failed -> (state.error.message ?: "") to true
    null -> (
        if (p.lastRefresh > 0) {
            stringResource(R.string.last_updated, android.text.format.DateUtils.getRelativeTimeSpanString(p.lastRefresh).toString())
        } else {
            stringResource(R.string.never_synced)
        }
    ) to false
}
