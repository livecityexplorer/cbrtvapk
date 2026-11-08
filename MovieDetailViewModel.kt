package app.iptv.feature.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.iptv.core.domain.model.ContentType
import app.iptv.core.domain.model.Movie
import app.iptv.core.domain.model.WatchHistory
import app.iptv.core.domain.repository.UserDataRepository
import app.iptv.core.domain.repository.VodDetailsLoader
import app.iptv.core.domain.repository.VodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MovieState {
    data object Loading : MovieState
    data object Missing : MovieState
    data class Found(val movie: Movie) : MovieState
}

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val vod: VodRepository,
    private val details: VodDetailsLoader,
    userData: UserDataRepository
) : ViewModel() {
    private val movieId: Long = handle.get<Long>("id") ?: -1L

    val movie: StateFlow<MovieState> = vod.observeMovie(movieId)
        .map { if (it == null) MovieState.Missing else MovieState.Found(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MovieState.Loading)

    val progress: StateFlow<WatchHistory?> = userData.observeProgress(ContentType.MOVIE, movieId.toString())
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _loadingDetails = MutableStateFlow(false)
    val loadingDetails: StateFlow<Boolean> = _loadingDetails.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { loadDetails() }

    /** Plot/cast/year come from get_vod_info the first time a title is opened; the screen updates itself when stored. */
    fun loadDetails() {
        viewModelScope.launch {
            val m = vod.getMovie(movieId) ?: return@launch
            _loadingDetails.value = true
            _error.value = null
            try {
                details.loadMovie(m)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e.message ?: "Unknown error"
            } finally {
                _loadingDetails.value = false
            }
        }
    }
}
