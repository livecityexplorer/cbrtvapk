package app.iptv.feature.vod

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.model.Movie
import app.iptv.core.domain.model.WatchHistory
import app.iptv.core.domain.util.ResumePolicy
import app.iptv.core.ui.CenteredLoading
import app.iptv.core.ui.CenteredMessage
import coil.compose.AsyncImage

@Composable
fun MovieDetailScreen(onPlay: (movieId: Long, resume: Boolean) -> Unit, viewModel: MovieDetailViewModel = hiltViewModel()) {
    val state by viewModel.movie.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val loading by viewModel.loadingDetails.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    when (val s = state) {
        MovieState.Loading -> CenteredLoading()
        MovieState.Missing -> CenteredMessage(stringResource(R.string.movie_missing))
        is MovieState.Found -> MovieContent(s.movie, progress, loading, error, onPlay)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MovieContent(movie: Movie, progress: WatchHistory?, loadingDetails: Boolean, error: String?, onPlay: (Long, Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) { } }
    val resumeAt = progress?.let { ResumePolicy.startPosition(it.positionMs, it.durationMs) } ?: 0L

    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        AsyncImage(
            model = movie.posterUrl, contentDescription = movie.name, contentScale = ContentScale.Crop,
            modifier = Modifier.width(260.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(movie.name, style = MaterialTheme.typography.headlineLarge)
            val meta = listOfNotNull(movie.year, movie.rating?.let { "★ " + "%.1f".format(it) }).joinToString("   ·   ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(movie.plot ?: stringResource(R.string.no_plot), style = MaterialTheme.typography.bodyLarge)
            movie.cast?.let { Text(stringResource(R.string.cast_label, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (loadingDetails) Text(stringResource(R.string.loading_details), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(stringResource(R.string.details_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (resumeAt > 0) {
                    Button(onClick = { onPlay(movie.id, true) }, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(R.string.resume_from, formatDuration(resumeAt)))
                    }
                    Button(onClick = { onPlay(movie.id, false) }) { Text(stringResource(R.string.start_over)) }
                } else {
                    Button(onClick = { onPlay(movie.id, true) }, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.play)) }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
