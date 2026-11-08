package app.iptv.player.parental

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.iptv.core.domain.repository.ParentalGate
import app.iptv.core.ui.PinDialog
import app.iptv.player.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ParentalGuardViewModel @Inject constructor(private val gate: ParentalGate) : ViewModel() {
    suspend fun channelNeedsPin(id: Long) = gate.channelNeedsPin(id)
    suspend fun movieNeedsPin(id: Long) = gate.movieNeedsPin(id)
    suspend fun episodeNeedsPin(id: Long) = gate.episodeNeedsPin(id)
    suspend fun verify(pin: String) = gate.verify(pin)
}

/**
 * Shows [content] only after the parental check passed: immediately when nothing is locked / the unlock window is open,
 * otherwise after a correct PIN. Cancelling calls [onDenied]. The result survives rotation via rememberSaveable.
 */
@Composable
fun PinGuard(
    key: Any,
    needsPin: suspend (ParentalGuardViewModel) -> Boolean,
    onDenied: () -> Unit,
    viewModel: ParentalGuardViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    var state by rememberSaveable(key) { mutableStateOf(CHECKING) }
    LaunchedEffect(key) { if (state == CHECKING) state = if (needsPin(viewModel)) PROMPT else ALLOWED }
    when (state) {
        ALLOWED -> content()
        else -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (state == PROMPT) PinDialog(
                title = stringResource(R.string.pin_required),
                onSubmit = viewModel::verify, onSuccess = { state = ALLOWED }, onDismiss = onDenied
            )
        }
    }
}

private const val CHECKING = 0
private const val PROMPT = 1
private const val ALLOWED = 2
