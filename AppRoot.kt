package app.iptv.player

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import app.iptv.core.domain.model.AppSettings
import app.iptv.player.navigation.IptvNavHost

/** First launch shows the legal notice (accept required); afterwards the app. */
@Composable
fun AppRoot(settings: AppSettings?, onAccept: () -> Unit, pendingChannelId: Long?, onPendingConsumed: () -> Unit) {
    when {
        settings == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        !settings.disclaimerAccepted -> DisclaimerScreen(onAccept)
        else -> IptvNavHost(pendingChannelId, onPendingConsumed)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.disclaimer_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp))
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onAccept, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.accept)) }
            Button(onClick = { activity?.finish() }) { Text(stringResource(R.string.decline)) }
        }
    }
}
