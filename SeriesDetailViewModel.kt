package app.iptv.feature.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.ContentType
import app.iptv.core.domain.model.Episode
import app.iptv.core.domain.model.Series
import app.iptv.core.domain.model.WatchHistory
import app.iptv.core.domain.repository.UserDataRepository
import app.iptv.core.domain.repository.VodDetailsLoader
import app.iptv.core.domain.repository.VodRepository
import app.iptv.core.domain.util.SeriesProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SeriesDetailUi(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val series: Series? = null,
    val episodes: List<Episode> = emptyList(),
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    /** episode id -> saved progress */
    val progress: Map<Long, WatchHistory> = emptyMap(),
    val continueEpisode: Episode? = null,
    val loadingDetails: Boolean = false,
    val error: String? = null
) {
    val seasonEpisodes: List<Episode> get() = episodes.filter { it.season == selectedSeason }
}

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val vod: VodRepository,
    private val details: VodDetailsLoader,
    userData: UserDataRepository
) : ViewModel() {
    private val seriesId: Long = handle.get<Long>("id") ?: -1L
    private val seasonKey = handle.getStateFlow(SEASON, -1)
    private val detailLoad = MutableStateFlow<Pair<Boolean, String?>>(false to null) // loading, error

    val ui: StateFlow<SeriesDetailUi> = combine(
        vod.observeSeries(seriesId),
        vod.observeEpisodes(seriesId),
        userData.observeProgressByType(ContentType.EPISODE).map { l -> l.associateBy { it.contentKey.toLongOrNull() ?: -1L } },
        seasonKey, detailLoad
    ) { series, episodes, progress, season, load ->
        val seasons = episodes.map { it.season }.distinct().sorted()
        val cont = SeriesProgress.pickContinue(episodes, progress) // episodes are ordered by season, number
        SeriesDetailUi(
            loading = false, missing = series == null, series = series, episodes = episodes, seasons = seasons,
            selectedSeason = season.takeIf { it in seasons } ?: cont?.season ?: seasons.firstOrNull(),
            progress = progress, continueEpisode = cont, loadingDetails = load.first, error = load.second
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SeriesDetailUi())

    init { loadDetails() }

    fun selectSeason(season: Int) { handle[SEASON] = season }

    /** Plot and the episode list come from get_series_info the first time a series is opened. */
    fun loadDetails() {
        viewModelScope.launch {
            val s = vod.getSeries(seriesId) ?: return@launch
            detailLoad.value = true to null
            detailLoad.value = try {
                details.loadSeries(s)
                false to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false to (e.message ?: "Unknown error")
            }
        }
    }

    private companion object { const val SEASON = "season" }
}
