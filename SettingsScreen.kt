package app.iptv.feature.settings

import android.content.ActivityNotFoundException
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.model.*
import app.iptv.core.ui.PinDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Text as M3Text

private enum class PinAction { LOCKS, CHANGE, REMOVE, HIDE_ADULT }

private sealed interface PinStep {
    data class Verify(val action: PinAction) : PinStep
    data class New(val current: String?) : PinStep
    data class Confirm(val current: String?, val new: String) : PinStep
}

private fun <T> List<T>.after(cur: T): T = this[(indexOf(cur) + 1).mod(size)]

/** Settings: playlists, EPG, playback, appearance, parental controls, backup/restore, about. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenPlaylists: () -> Unit,
    onOpenLocks: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val lastEpg by viewModel.lastEpgRefresh.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    var showAbout by remember { mutableStateOf(false) }
    var pinStep by remember { mutableStateOf<PinStep?>(null) }
    var pickerMissing by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }

    fun perform(action: PinAction, current: String?) {
        when (action) {
            PinAction.LOCKS -> onOpenLocks()
            PinAction.HIDE_ADULT -> viewModel.setHideAdult(!s.hideAdult)
            PinAction.CHANGE -> pinStep = PinStep.New(current)
            PinAction.REMOVE -> scope.launch { if (current != null) viewModel.clearPin(current) }
        }
    }
    /** CHANGE/REMOVE always ask for the current PIN (it is needed to change it); the others honour the unlock window. */
    fun requirePin(action: PinAction) {
        scope.launch {
            val always = action == PinAction.CHANGE || action == PinAction.REMOVE
            when {
                !s.hasPin && action == PinAction.CHANGE -> pinStep = PinStep.New(null)
                !s.hasPin -> perform(action, null)
                always || viewModel.isLockedNow() -> pinStep = PinStep.Verify(action)
                else -> perform(action, null)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium) }

            // ---- Playlists
            item { SettingRow(stringResource(R.string.settings_playlists), stringResource(R.string.settings_playlists_desc), onOpenPlaylists, Modifier.focusRequester(first)) }

            // ---- EPG
            item { Header(R.string.sec_epg) }
            item { SettingRow(stringResource(R.string.epg_auto), onOff(s.epgAutoRefresh), { viewModel.update { it.copy(epgAutoRefresh = !it.epgAutoRefresh) } }) }
            item {
                SettingRow(stringResource(R.string.epg_interval), stringResource(R.string.every_hours, s.epgIntervalHours),
                    { viewModel.update { it.copy(epgIntervalHours = AppSettings.INTERVAL_CHOICES_HOURS.after(it.epgIntervalHours.takeIf { h -> h in AppSettings.INTERVAL_CHOICES_HOURS } ?: 12)) } })
            }
            item {
                val last = if (lastEpg <= 0) stringResource(R.string.never) else DateUtils.getRelativeTimeSpanString(lastEpg, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                SettingRow(stringResource(R.string.epg_refresh_now), stringResource(R.string.epg_last, last), { viewModel.refreshGuideNow() })
            }

            // ---- Playback
            item { Header(R.string.sec_playback) }
            item { SettingRow(stringResource(R.string.pb_buffer), stringResource(bufferLabel(s.bufferSize)), { viewModel.update { it.copy(bufferSize = BufferSize.entries.after(it.bufferSize)) } }) }
            item { SettingRow(stringResource(R.string.pb_decoder), stringResource(decoderLabel(s.decoder)), { viewModel.update { it.copy(decoder = DecoderMode.entries.after(it.decoder)) } }) }
            item { SettingRow(stringResource(R.string.pb_aspect), stringResource(aspectLabel(s.defaultAspect)), { viewModel.update { it.copy(defaultAspect = AspectMode.entries.after(it.defaultAspect)) } }) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.update { it.copy(subtitleSizeSp = (it.subtitleSizeSp - 2).coerceAtLeast(AppSettings.SUBTITLE_MIN_SP)) } }) { Text("−") }
                    Text(stringResource(R.string.pb_sub_size, s.subtitleSizeSp), Modifier.widthIn(min = 200.dp), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { viewModel.update { it.copy(subtitleSizeSp = (it.subtitleSizeSp + 2).coerceAtMost(AppSettings.SUBTITLE_MAX_SP)) } }) { Text("+") }
                }
            }
            item { SettingRow(stringResource(R.string.pb_sub_color), stringResource(colorLabel(s.subtitleColor)), { viewModel.update { it.copy(subtitleColor = SubtitleColor.entries.after(it.subtitleColor)) } }) }

            // ---- Appearance
            item { Header(R.string.sec_appearance) }
            item { SettingRow(stringResource(R.string.theme), stringResource(if (s.theme == ThemeMode.AMOLED) R.string.theme_amoled else R.string.theme_dark), { viewModel.update { it.copy(theme = ThemeMode.entries.after(it.theme)) } }) }

            // ---- Parental
            item { Header(R.string.sec_parental) }
            item {
                SettingRow(stringResource(if (s.hasPin) R.string.pin_change else R.string.pin_set),
                    stringResource(if (s.hasPin) R.string.pin_is_set else R.string.pin_not_set), { requirePin(PinAction.CHANGE) })
            }
            if (s.hasPin) {
                item { SettingRow(stringResource(R.string.pin_remove), null, { requirePin(PinAction.REMOVE) }) }
                item { SettingRow(stringResource(R.string.pin_lock_now), stringResource(R.string.pin_lock_now_desc), { viewModel.lockNow() }) }
            }
            item {
                SettingRow(stringResource(R.string.locks_manage), stringResource(if (s.hasPin) R.string.locks_manage_desc else R.string.locks_need_pin),
                    { if (s.hasPin) requirePin(PinAction.LOCKS) else viewModel.notify(R.string.msg_set_pin_first) })
            }
            item { SettingRow(stringResource(R.string.hide_adult), onOff(s.hideAdult) + " — " + stringResource(R.string.hide_adult_desc), { requirePin(PinAction.HIDE_ADULT) }) }

            // ---- Backup
            item { Header(R.string.sec_backup) }
            item {
                SettingRow(stringResource(R.string.backup_export), stringResource(R.string.backup_export_desc), {
                    try { exportLauncher.launch("iptv-player-backup.json") } catch (e: ActivityNotFoundException) { pickerMissing = true }
                })
            }
            item {
                SettingRow(stringResource(R.string.backup_import), stringResource(R.string.backup_import_desc), {
                    try { importLauncher.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { pickerMissing = true }
                })
            }

            // ---- About
            item { Header(R.string.sec_about) }
            item { SettingRow(stringResource(R.string.about_title), stringResource(R.string.about_desc), { showAbout = true }) }
            item { Spacer(Modifier.height(48.dp)) }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        val msg = message
        if (msg != null || pickerMissing) {
            LaunchedEffect(msg?.id, pickerMissing) { delay(4_000); viewModel.consumeMessage(); pickerMissing = false }
            Text(
                if (msg != null) stringResource(msg.resId, *msg.args.toTypedArray()) else stringResource(R.string.msg_no_file_picker),
                Modifier.align(Alignment.BottomCenter).padding(24.dp), style = MaterialTheme.typography.titleMedium
            )
        }
    }

    // ---- PIN dialogs
    when (val step = pinStep) {
        is PinStep.Verify -> {
            var captured by remember { mutableStateOf("") }
            PinDialog(
                title = stringResource(R.string.pin_enter_current),
                onSubmit = { pin -> viewModel.verifyPin(pin).also { if (it) captured = pin } },
                onSuccess = { pinStep = null; perform(step.action, captured) },
                onDismiss = { pinStep = null }
            )
        }
        is PinStep.New -> {
            var entered by remember { mutableStateOf("") }
            PinDialog(
                title = stringResource(R.string.pin_enter_new), subtitle = stringResource(R.string.pin_rules),
                onSubmit = { entered = it; true },
                onSuccess = { pinStep = PinStep.Confirm(step.current, entered) },
                onDismiss = { pinStep = null }
            )
        }
        is PinStep.Confirm -> PinDialog(
            title = stringResource(R.string.pin_confirm), errorText = stringResource(R.string.pin_mismatch),
            onSubmit = { pin -> pin == step.new && viewModel.setPin(step.new, step.current) },
            onSuccess = { pinStep = null }, onDismiss = { pinStep = null }
        )
        null -> Unit
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { M3Text(stringResource(R.string.restore_title)) },
            text = { M3Text(stringResource(R.string.restore_message)) },
            confirmButton = { TextButton(onClick = { viewModel.importFrom(uri); confirmRestore = null }) { M3Text(stringResource(R.string.restore_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { M3Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showAbout) {
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0" }
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { M3Text(stringResource(R.string.about_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3Text(stringResource(R.string.about_version, version))
                    M3Text(stringResource(R.string.about_disclaimer))
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { M3Text(stringResource(R.string.close)) } }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Header(res: Int) {
    Spacer(Modifier.height(12.dp))
    Text(stringResource(res), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingRow(title: String, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (value != null) Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun onOff(on: Boolean) = stringResource(if (on) R.string.on else R.string.off)

private fun bufferLabel(b: BufferSize) = when (b) { BufferSize.SMALL -> R.string.buf_small; BufferSize.MEDIUM -> R.string.buf_medium; BufferSize.LARGE -> R.string.buf_large }
private fun decoderLabel(d: DecoderMode) = if (d == DecoderMode.HARDWARE) R.string.dec_hw else R.string.dec_sw
private fun colorLabel(c: SubtitleColor) = when (c) { SubtitleColor.WHITE -> R.string.col_white; SubtitleColor.YELLOW -> R.string.col_yellow; SubtitleColor.GREEN -> R.string.col_green; SubtitleColor.CYAN -> R.string.col_cyan }
private fun aspectLabel(a: AspectMode) = when (a) {
    AspectMode.FIT -> R.string.set_aspect_fit; AspectMode.ZOOM -> R.string.set_aspect_zoom; AspectMode.STRETCH -> R.string.set_aspect_stretch
    AspectMode.RATIO_16_9 -> R.string.set_aspect_16_9; AspectMode.RATIO_4_3 -> R.string.set_aspect_4_3
}
