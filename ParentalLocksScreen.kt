package app.iptv.feature.settings.parental

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.model.CategoryKind
import app.iptv.feature.settings.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ParentalLocksScreen(viewModel: ParentalLocksViewModel = hiltViewModel()) {
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val cats by viewModel.categories.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.locks_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.locks_hint), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryKind.entries.forEachIndexed { i, k ->
                Button(onClick = { viewModel.select(k) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                    Text((if (k == kind) "• " else "") + stringResource(kindLabel(k)))
                }
            }
        }
        val list = cats
        when {
            list == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(stringResource(R.string.locks_empty), style = MaterialTheme.typography.bodyLarge)
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { c ->
                    Button(onClick = { viewModel.toggle(c) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(stringResource(if (c.isLocked) R.string.locks_locked else R.string.locks_unlocked))
                        }
                    }
                }
            }
        }
    }
}

private fun kindLabel(k: CategoryKind) = when (k) {
    CategoryKind.LIVE -> R.string.kind_live
    CategoryKind.MOVIE -> R.string.kind_movies
    CategoryKind.SERIES -> R.string.kind_series
}
