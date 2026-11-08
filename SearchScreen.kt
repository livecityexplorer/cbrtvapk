package app.iptv.feature.search

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.model.Channel
import app.iptv.core.domain.model.Movie
import app.iptv.core.domain.model.ProgramHit
import app.iptv.core.domain.model.Series
import app.iptv.core.ui.CenteredMessage
import app.iptv.core.ui.ChannelLogo
import app.iptv.core.ui.PosterCard
import app.iptv.core.ui.PosterItem
import androidx.compose.material3.Text as M3Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    onPlayChannel: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onAddPlaylist: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { inputFocus.requestFocus() } catch (e: Exception) { } }

    var dialog by remember { mutableStateOf<ProgramHit?>(null) }
    var pendingReminder by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingReminder?.invoke(); pendingReminder = null
    }
    fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { pendingReminder = action; permission.launch(Manifest.permission.POST_NOTIFICATIONS) } else action()
    }

    if (ui.status == SearchStatus.NO_PLAYLIST) {
        CenteredMessage(stringResource(R.string.no_playlist_title), stringResource(R.string.no_playlist_message), stringResource(R.string.add_playlist), onAddPlaylist)
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
            OutlinedTextField(
                value = query, onValueChange = viewModel::setQuery, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                label = { M3Text(stringResource(R.string.search_hint)) },
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { viewModel.setQuery("") }) { M3Text(stringResource(R.string.search_clear)) }
                }
            )
            Spacer(Modifier.height(8.dp))

            when (ui.status) {
                SearchStatus.IDLE -> Text(stringResource(R.string.search_idle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SearchStatus.ERROR -> Text(stringResource(R.string.search_error, ui.error.orEmpty()), color = MaterialTheme.colorScheme.error)
                SearchStatus.DONE, SearchStatus.SEARCHING -> {
                    if (ui.status == SearchStatus.SEARCHING) Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodySmall)
                    if (ui.status == SearchStatus.DONE && ui.results.isEmpty) {
                        Text(stringResource(R.string.search_no_results, query.trim()), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Results(ui.results.channels, ui.results.programs, ui.results.movies, ui.results.series,
                            onChannel = { onPlayChannel(it.id) }, onProgram = { dialog = it },
                            onMovie = { onOpenMovie(it.id) }, onSeries = { onOpenSeries(it.id) })
                    }
                }
                SearchStatus.NO_PLAYLIST -> Unit
            }
        }
    }

    dialog?.let { hit ->
        val reminderSet by remember(hit.channel.id, hit.program.startTime) { viewModel.reminderScheduled(hit.channel, hit.program) }.collectAsState(initial = false)
        val isFuture = hit.program.startTime > System.currentTimeMillis()
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { M3Text(hit.program.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3Text(hit.channel.name + " · " + timeRange(hit))
                    M3Text(hit.program.description ?: stringResource(R.string.dlg_no_description))
                    TextButton(onClick = { dialog = null; onPlayChannel(hit.channel.id) }) { M3Text(stringResource(R.string.dlg_watch_live)) }
                    if (isFuture) {
                        TextButton(onClick = {
                            if (reminderSet) viewModel.cancelReminder(hit.channel, hit.program)
                            else withNotificationPermission { viewModel.setReminder(hit.channel, hit.program) }
                        }) { M3Text(stringResource(if (reminderSet) R.string.dlg_cancel_reminder else R.string.dlg_remind)) }
                        if (reminderSet) M3Text(stringResource(R.string.dlg_reminder_set))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { M3Text(stringResource(R.string.dlg_close)) } }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Results(
    channels: List<Channel>, programs: List<ProgramHit>, movies: List<Movie>, series: List<Series>,
    onChannel: (Channel) -> Unit, onProgram: (ProgramHit) -> Unit, onMovie: (Movie) -> Unit, onSeries: (Series) -> Unit
) {
    val now = remember(programs) { System.currentTimeMillis() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (channels.isNotEmpty()) {
            item(key = "h-channels") { Header(stringResource(R.string.section_channels, channels.size)) }
            items(channels, key = { "c${it.id}" }) { ChannelRow(it) { onChannel(it) } }
        }
        if (programs.isNotEmpty()) {
            item(key = "h-programs") { Header(stringResource(R.string.section_programs, programs.size)) }
            items(programs, key = { "p${it.channel.id}-${it.program.startTime}" }) { ProgramRow(it, it.program.isLiveAt(now)) { onProgram(it) } }
        }
        if (movies.isNotEmpty()) {
            item(key = "h-movies") { Header(stringResource(R.string.section_movies, movies.size)) }
            item(key = "movies") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(movies, key = { it.id }) { m ->
                        PosterCard(PosterItem(m.id, m.name, m.posterUrl, subtitle(m.year, m.rating)), onClick = { onMovie(m) }, modifier = Modifier.width(130.dp))
                    }
                }
            }
        }
        if (series.isNotEmpty()) {
            item(key = "h-series") { Header(stringResource(R.string.section_series, series.size)) }
            item(key = "series") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(series, key = { it.id }) { s ->
                        PosterCard(PosterItem(s.id, s.name, s.posterUrl, subtitle(s.year, s.rating)), onClick = { onSeries(s) }, modifier = Modifier.width(130.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Header(text: String) = Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(channel: Channel, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 72.dp, height = 44.dp))
            Text(channel.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgramRow(hit: ProgramHit, live: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChannelLogo(hit.channel.logoUrl, hit.channel.name, Modifier.size(width = 72.dp, height = 44.dp))
            Column(Modifier.weight(1f)) {
                Text(hit.program.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hit.channel.name + " · " + timeRange(hit), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (live) Text(stringResource(R.string.live_now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun timeRange(hit: ProgramHit): String {
    val ctx = LocalContext.current
    return remember(hit.program.startTime, hit.program.endTime) {
        DateUtils.formatDateTime(ctx, hit.program.startTime, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL) +
            " – " + DateUtils.formatDateTime(ctx, hit.program.endTime, DateUtils.FORMAT_SHOW_TIME)
    }
}

private fun subtitle(year: String?, rating: Float?): String? =
    listOfNotNull(year, rating?.let { "★ " + "%.1f".format(it) }).joinToString(" · ").ifEmpty { null }
