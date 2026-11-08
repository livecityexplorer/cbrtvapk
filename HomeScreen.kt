package app.iptv.player.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import app.iptv.core.domain.model.ContentType
import app.iptv.core.ui.PosterCard
import app.iptv.core.ui.PosterItem
import app.iptv.player.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onPlayChannel: (Long) -> Unit,
    onResume: (kind: String, id: Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onAddPlaylist: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    when {
        ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        !ui.hasPlaylist -> Message(stringResource(R.string.home_no_playlist_title), stringResource(R.string.home_no_playlist_message), stringResource(R.string.home_add_playlist), onAddPlaylist)
        ui.isEmpty -> Message(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_message), null, {})
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text(stringResource(R.string.nav_home), style = MaterialTheme.typography.headlineMedium) }
            if (ui.continueWatching.isNotEmpty()) item {
                ContentRow(R.string.home_continue) {
                    items(ui.continueWatching, key = { it.id }) { h ->
                        val progress = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else null
                        PosterCard(
                            PosterItem(h.id, h.title, h.imageUrl, progress = progress),
                            onClick = { onResume(if (h.type == ContentType.EPISODE) "episode" else "movie", h.contentKey.toLongOrNull() ?: return@PosterCard) },
                            modifier = Modifier.width(140.dp)
                        )
                    }
                }
            }
            if (ui.favorites.isNotEmpty()) item {
                ContentRow(R.string.home_favorites) {
                    items(ui.favorites, key = { it.id }) { c ->
                        Card(onClick = { onPlayChannel(c.id) }, modifier = Modifier.width(180.dp)) {
                            Box(Modifier.fillMaxWidth().height(90.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            if (ui.recentMovies.isNotEmpty()) item {
                ContentRow(R.string.home_recent_movies) {
                    items(ui.recentMovies, key = { it.id }) { m ->
                        PosterCard(PosterItem(m.id, m.name, m.posterUrl, m.year), onClick = { onOpenMovie(m.id) }, modifier = Modifier.width(140.dp))
                    }
                }
            }
            if (ui.recentSeries.isNotEmpty()) item {
                ContentRow(R.string.home_recent_series) {
                    items(ui.recentSeries, key = { it.id }) { s ->
                        PosterCard(PosterItem(s.id, s.name, s.posterUrl, s.year), onClick = { onOpenSeries(s.id) }, modifier = Modifier.width(140.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContentRow(title: Int, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp), content = content)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Message(title: String, message: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); Button(onClick = onAction) { Text(action) } }
    }
}
