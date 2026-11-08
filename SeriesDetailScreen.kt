package app.iptv.feature.series

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.model.Episode
import app.iptv.core.domain.model.WatchHistory
import app.iptv.core.domain.util.ResumePolicy
import app.iptv.core.ui.CenteredLoading
import app.iptv.core.ui.CenteredMessage
import coil.compose.AsyncImage
import androidx.compose.material3.Text as M3Text

@Composable
fun SeriesDetailScreen(onPlayEpisode: (episodeId: Long, resume: Boolean) -> Unit, viewModel: SeriesDetailViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    when {
        ui.loading -> CenteredLoading()
        ui.missing -> CenteredMessage(stringResource(R.string.series_missing))
        else -> SeriesContent(ui, viewModel::selectSeason, onPlayEpisode)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeriesContent(ui: SeriesDetailUi, onSeason: (Int) -> Unit, onPlay: (Long, Boolean) -> Unit) {
    val series = ui.series ?: return
    val focus = remember { FocusRequester() }
    // Start on "Continue"; if there are no episodes yet it is not composed and focus stays where Compose puts it.
    LaunchedEffect(ui.continueEpisode?.id) { if (ui.continueEpisode != null) try { focus.requestFocus() } catch (e: Exception) { } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                AsyncImage(
                    model = series.posterUrl, contentDescription = series.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(series.name, style = MaterialTheme.typography.headlineLarge)
                    val meta = listOfNotNull(series.year, series.rating?.let { "★ " + "%.1f".format(it) }).joinToString("   ·   ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(series.plot ?: stringResource(R.string.no_plot), style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    series.cast?.let {
                        Text(stringResource(R.string.cast_label, it), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (ui.loadingDetails) Text(stringResource(R.string.loading_details), style = MaterialTheme.typography.bodySmall)
                    ui.error?.let { Text(stringResource(R.string.details_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

                    ui.continueEpisode?.let { ep ->
                        val saved = ui.progress[ep.id]
                        val resuming = saved != null && ResumePolicy.startPosition(saved.positionMs, saved.durationMs) > 0
                        Button(onClick = { onPlay(ep.id, true) }, modifier = Modifier.focusRequester(focus)) {
                            Text(stringResource(if (resuming) R.string.continue_episode else R.string.play_episode, code(ep)))
                        }
                    }
                }
            }
        }

        if (ui.seasons.size > 1) {
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.seasons, key = { it }) { season ->
                        FilterChip(selected = season == ui.selectedSeason, onClick = { onSeason(season) },
                            label = { M3Text(stringResource(R.string.season_label, season)) })
                    }
                }
            }
        }

        if (ui.episodes.isEmpty() && !ui.loadingDetails) {
            item { Text(stringResource(R.string.no_episodes), style = MaterialTheme.typography.bodyLarge) }
        }

        items(ui.seasonEpisodes, key = { it.id }) { ep -> EpisodeRow(ep, ui.progress[ep.id]) { onPlay(ep.id, true) } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeRow(episode: Episode, saved: WatchHistory?, onClick: () -> Unit) {
    val finished = saved != null && ResumePolicy.isFinished(saved.positionMs, saved.durationMs)
    val progress = saved?.let { ResumePolicy.progress(it.positionMs, it.durationMs) } ?: 0f
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(144.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (!episode.imageUrl.isNullOrBlank()) {
                    AsyncImage(model = episode.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (progress > 0f && !finished) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x88000000))) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.episode_title, episode.episodeNum, episode.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                episode.plot?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (episode.durationSecs > 0) Text(stringResource(R.string.minutes_short, episode.durationSecs / 60), style = MaterialTheme.typography.bodySmall)
            }
            if (finished) Text("✓ " + stringResource(R.string.watched), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun code(e: Episode) = "S" + e.season.toString().padStart(2, '0') + "E" + e.episodeNum.toString().padStart(2, '0')
