package app.iptv.feature.series

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.iptv.core.ui.BrowseLabels
import app.iptv.core.ui.CenteredLoading
import app.iptv.core.ui.CenteredMessage
import app.iptv.core.ui.PosterBrowse

@Composable
fun SeriesScreen(
    onOpenSeries: (Long) -> Unit,
    onResumeEpisode: (Long) -> Unit,
    onAddPlaylist: () -> Unit,
    viewModel: SeriesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val items = viewModel.series.collectAsLazyPagingItems()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()

    when {
        state.loading -> CenteredLoading()
        !state.hasPlaylist -> CenteredMessage(
            stringResource(R.string.no_playlist_title), stringResource(R.string.no_playlist_message),
            stringResource(R.string.add_playlist), onAddPlaylist
        )
        else -> PosterBrowse(
            categories = state.categories, selectedCategoryId = state.categoryId, onSelectCategory = viewModel::selectCategory,
            query = query, onQueryChange = viewModel::setQuery,
            items = items, continueWatching = continueWatching,
            onOpen = { onOpenSeries(it.id) }, onOpenContinue = { onResumeEpisode(it.id) },
            labels = BrowseLabels(
                allCategories = stringResource(R.string.all_series), search = stringResource(R.string.search_series),
                continueWatching = stringResource(R.string.continue_watching), empty = stringResource(R.string.series_empty),
                noMatch = stringResource(R.string.series_no_match), loadError = stringResource(R.string.load_error),
                retry = stringResource(R.string.retry)
            )
        )
    }
}
