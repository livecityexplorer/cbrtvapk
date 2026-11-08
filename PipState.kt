package app.iptv.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Bridge between the player (ViewModel) and MainActivity, which owns the Picture-in-Picture window. */
@Singleton
class PipState @Inject constructor() {
    data class Params(val eligible: Boolean = false, val videoAspect: Float = 0f, val playing: Boolean = false)

    private val _params = MutableStateFlow(Params())
    val params: StateFlow<Params> = _params.asStateFlow()

    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    /** Called by the PiP window's play/pause button (main thread). */
    @Volatile var onTogglePlayPause: (() -> Unit)? = null

    fun update(eligible: Boolean, aspect: Float, playing: Boolean) { _params.value = Params(eligible, aspect, playing) }
    fun setInPip(value: Boolean) { _inPip.value = value }
    fun clear() { _params.value = Params(); onTogglePlayPause = null }
}
