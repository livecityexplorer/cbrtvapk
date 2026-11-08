package app.iptv.feature.epg

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.*
import app.iptv.core.domain.model.Channel
import app.iptv.core.domain.model.EpgProgram
import app.iptv.core.domain.model.Recording
import app.iptv.core.domain.model.RecordingStatus
import app.iptv.core.domain.repository.InvalidReason
import app.iptv.core.domain.repository.ScheduleResult
import app.iptv.core.domain.util.Catchup
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import androidx.compose.material3.Text as M3Text

private const val DP_PER_MIN = 5f
private const val MINUTE_MS = 60_000f
private const val HALF_HOUR_MS = 1_800_000L
private val ROW_HEIGHT = 64.dp
private val CHANNEL_COL = 220.dp

private fun xDp(time: Long, windowStart: Long): Dp = ((time - windowStart) / MINUTE_MS * DP_PER_MIN).dp

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EpgScreen(
    onWatch: (channelId: Long, start: Long?, end: Long?) -> Unit,
    onAddPlaylist: () -> Unit,
    viewModel: EpgViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()
    val now by rememberNow()
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val hScroll = rememberScrollState()       // shared by the time axis and every row, so they scroll together
    val listState = rememberLazyListState()
    val windowStart = viewModel.windowStart
    val totalWidth = xDp(viewModel.windowEnd, windowStart)

    var selected by remember { mutableStateOf<Pair<Channel, EpgProgram>?>(null) }
    var showCategories by remember { mutableStateOf(false) }
    var lastChannelId by rememberSaveable { mutableLongStateOf(-1L) }
    var lastStart by rememberSaveable { mutableLongStateOf(-1L) }
    var restored by remember { mutableStateOf(lastChannelId < 0) }

    fun nowScrollPx(): Int = with(density) { (xDp(System.currentTimeMillis(), windowStart) - (30 * DP_PER_MIN).dp).toPx() }.toInt().coerceAtLeast(0)
    // Start near "now", but keep the position when coming back from the player.
    LaunchedEffect(Unit) {
        if (hScroll.value == 0 && lastChannelId < 0) {
            snapshotFlow { hScroll.maxValue }.first { it > 0 }
            hScroll.scrollTo(nowScrollPx())
        }
    }

    // POST_NOTIFICATIONS (API 33+) is asked for the first time a reminder is set.
    var pendingReminder by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingReminder?.invoke(); pendingReminder = null
    }
    fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { pendingReminder = action; permission.launch(Manifest.permission.POST_NOTIFICATIONS) } else action()
    }

    val active = ui.active
    when {
        ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        active == null -> NoPlaylist(onAddPlaylist)
        else -> Column(Modifier.fillMaxSize().padding(top = 16.dp, end = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.epg_title), style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { showCategories = true }) {
                    Text(ui.categories.firstOrNull { it.id == ui.categoryId }?.name ?: stringResource(R.string.epg_all_channels), maxLines = 1)
                }
                Button(onClick = { scope.launch { hScroll.animateScrollTo(nowScrollPx()) } }) { Text(stringResource(R.string.epg_now)) }
                Button(onClick = viewModel::refreshEpg, enabled = ui.refresh !is EpgRefreshUi.Running) { Text(stringResource(R.string.epg_refresh)) }
                Text(refreshStatus(ui.refresh), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            // time axis
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(CHANNEL_COL + 24.dp))
                Box(Modifier.weight(1f).horizontalScroll(hScroll)) { TimeAxis(windowStart, viewModel.windowEnd, now, totalWidth) }
            }

            val refresh = channels.loadState.refresh
            when {
                refresh is LoadState.Error && channels.itemCount == 0 -> Column(
                    Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.epg_load_error), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { channels.retry() }) { Text(stringResource(R.string.epg_retry)) }
                }
                refresh is LoadState.Loading && channels.itemCount == 0 ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                channels.itemCount == 0 ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.epg_no_channels)) }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(channels.itemCount, key = channels.itemKey { it.id }) { index ->
                        val ch = channels[index] ?: return@items
                        val programs by remember(ch.id) { viewModel.programs(ch) }.collectAsState(initial = emptyList())
                        EpgRow(
                            channel = ch, programs = programs, windowStart = windowStart, windowEnd = viewModel.windowEnd,
                            now = now, totalWidth = totalWidth, hScroll = hScroll,
                            restoreStart = if (!restored && ch.id == lastChannelId) lastStart else null,
                            onRestored = { restored = true },
                            onProgramFocused = { start -> lastChannelId = ch.id; lastStart = start },
                            onChannelClick = { onWatch(ch.id, null, null) },
                            onProgramClick = { selected = ch to it }
                        )
                    }
                }
            }
        }
    }

    selected?.let { (ch, p) ->
        val reminderSet by remember(ch.id, p.startTime) { viewModel.reminderScheduled(ch, p) }.collectAsState(initial = false)
        val recording by remember(ch.id, p.startTime) { viewModel.recordingFor(ch, p) }.collectAsState(initial = null)
        var recordResult by remember(ch.id, p.startTime) { mutableStateOf<ScheduleResult?>(null) }
        ProgramDialog(
            channel = ch, program = p, now = now, reminderSet = reminderSet,
            recording = recording, recordResult = recordResult,
            onRecord = { scope.launch { recordResult = viewModel.record(ch, p) } },
            onStopRecording = { recording?.let { viewModel.stopRecording(it.id) } },
            onWatchLive = { selected = null; onWatch(ch.id, null, null) },
            onCatchup = { selected = null; onWatch(ch.id, p.startTime, p.endTime) },
            onRemind = { withNotificationPermission { viewModel.setReminder(ch, p) } },
            onCancelReminder = { viewModel.cancelReminder(ch, p) },
            onDismiss = { selected = null }
        )
    }
    if (showCategories) {
        AlertDialog(
            onDismissRequest = { showCategories = false },
            title = { M3Text(stringResource(R.string.epg_choose_category)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { viewModel.selectCategory(null); showCategories = false }) { M3Text(stringResource(R.string.epg_all_channels)) }
                    ui.categories.forEach { c ->
                        TextButton(onClick = { viewModel.selectCategory(c.id); showCategories = false }) { M3Text(c.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCategories = false }) { M3Text(stringResource(R.string.dlg_close)) } }
        )
    }
}

// ------------------------------------------------------------------ grid pieces

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimeAxis(windowStart: Long, windowEnd: Long, now: Long, totalWidth: Dp) {
    val context = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    Box(Modifier.width(totalWidth).height(28.dp)) {
        var t = windowStart
        while (t < windowEnd) {
            Text(fmt.format(Date(t)), Modifier.offset(x = xDp(t, windowStart)).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
            t += HALF_HOUR_MS
        }
        Box(Modifier.offset(x = xDp(now, windowStart)).width(2.dp).fillMaxHeight().background(Color(0xFFFF5252)))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EpgRow(
    channel: Channel, programs: List<EpgProgram>, windowStart: Long, windowEnd: Long, now: Long, totalWidth: Dp,
    hScroll: androidx.compose.foundation.ScrollState,
    restoreStart: Long?, onRestored: () -> Unit, onProgramFocused: (Long) -> Unit,
    onChannelClick: () -> Unit, onProgramClick: (EpgProgram) -> Unit
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(24.dp))
        ChannelCell(channel, onChannelClick)
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                if (programs.isEmpty()) {
                    // One block around "now" (a full-width block would make bring-into-view jump to the far left).
                    val from = now - HALF_HOUR_MS
                    Block(
                        offsetX = xDp(maxOf(from, windowStart), windowStart), width = (180 * DP_PER_MIN).dp,
                        title = stringResource(R.string.epg_no_info), timeText = "", isNow = false, progress = 0f,
                        isRestoreTarget = false, onRestored = {}, onFocused = {}, onClick = onChannelClick
                    )
                } else {
                    programs.forEach { p ->
                        val s = maxOf(p.startTime, windowStart)
                        val e = minOf(p.endTime, windowEnd)
                        if (e > s) {
                            Block(
                                offsetX = xDp(s, windowStart), width = (((e - s) / MINUTE_MS * DP_PER_MIN) - 2f).coerceAtLeast(24f).dp,
                                title = p.title, timeText = timeRange(p), isNow = p.isLiveAt(now), progress = p.progressAt(now),
                                isRestoreTarget = restoreStart == p.startTime, onRestored = onRestored,
                                onFocused = { onProgramFocused(p.startTime) }, onClick = { onProgramClick(p) }
                            )
                        }
                    }
                }
                Box(Modifier.offset(x = xDp(now, windowStart)).width(2.dp).fillMaxHeight().background(Color(0xFFFF5252)))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun Block(
    offsetX: Dp, width: Dp, title: String, timeText: String, isNow: Boolean, progress: Float,
    isRestoreTarget: Boolean, onRestored: () -> Unit, onFocused: () -> Unit, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bring = remember { BringIntoViewRequester() }
    val restorer = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val marginPx = with(LocalDensity.current) { 96.dp.toPx() }
    val shape = RoundedCornerShape(6.dp)

    LaunchedEffect(isRestoreTarget) {
        if (isRestoreTarget) { try { restorer.requestFocus(); onRestored() } catch (e: Exception) { /* not attached yet */ } }
    }

    Column(
        Modifier
            .offset(x = offsetX).width(width).fillMaxHeight().padding(top = 2.dp, bottom = 2.dp, end = 2.dp)
            .bringIntoViewRequester(bring)
            .onSizeChanged { size = it }
            .focusRequester(restorer)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                    // ask for a bit of margin so the neighbouring block peeks in as you move along the row
                    scope.launch { bring.bringIntoView(Rect(-marginPx, 0f, size.width + marginPx, size.height.toFloat())) }
                }
            }
            .clip(shape)
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.surfaceVariant
                    isNow -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (timeText.isNotEmpty()) {
            Text(timeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isNow) {
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelCell(channel: Channel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .width(CHANNEL_COL).fillMaxHeight().padding(end = 4.dp, top = 2.dp, bottom = 2.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(width = 44.dp, height = 30.dp), contentAlignment = Alignment.Center) {
            if (channel.logoUrl.isNullOrBlank()) Text(initials(channel.name), style = MaterialTheme.typography.bodySmall)
            else SubcomposeAsyncImage(
                model = channel.logoUrl, contentDescription = channel.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = { Text(initials(channel.name), style = MaterialTheme.typography.bodySmall) },
                error = { Text(initials(channel.name), style = MaterialTheme.typography.bodySmall) }
            )
        }
        Text(channel.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ dialog

@Composable
private fun ProgramDialog(
    channel: Channel, program: EpgProgram, now: Long, reminderSet: Boolean,
    recording: Recording?, recordResult: ScheduleResult?, onRecord: () -> Unit, onStopRecording: () -> Unit,
    onWatchLive: () -> Unit, onCatchup: () -> Unit, onRemind: () -> Unit, onCancelReminder: () -> Unit, onDismiss: () -> Unit
) {
    val isFuture = program.startTime > now
    val canCatchup = Catchup.isAvailable(channel, program, now)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text(program.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                M3Text("${channel.name} · ${timeRange(program)}")
                program.posterUrl?.takeIf { it.isNotBlank() }?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(140.dp))
                }
                Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    M3Text(program.description ?: stringResource(R.string.dlg_no_description))
                }
                TextButton(onClick = onWatchLive) { M3Text(stringResource(R.string.dlg_watch_live)) }
                if (canCatchup) TextButton(onClick = onCatchup) { M3Text(stringResource(R.string.dlg_watch_from_start)) }
                if (isFuture) {
                    TextButton(onClick = if (reminderSet) onCancelReminder else onRemind) {
                        M3Text(stringResource(if (reminderSet) R.string.dlg_cancel_reminder else R.string.dlg_remind))
                    }
                    if (reminderSet) M3Text(stringResource(R.string.dlg_reminder_set))
                }
                if (program.endTime > now) {
                    if (recording == null) {
                        TextButton(onClick = onRecord) { M3Text(stringResource(R.string.dlg_record)) }
                    } else {
                        TextButton(onClick = onStopRecording) {
                            M3Text(stringResource(if (recording.status == RecordingStatus.RECORDING) R.string.dlg_stop_recording else R.string.dlg_cancel_recording))
                        }
                    }
                    recordResult?.let { M3Text(recordMessage(it)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { M3Text(stringResource(R.string.dlg_close)) } }
    )
}

// ------------------------------------------------------------------ states & helpers

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NoPlaylist(onAdd: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) { } }
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.epg_no_playlist_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.epg_no_playlist_message))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.epg_add_playlist)) }
    }
}

@Composable
private fun recordMessage(r: ScheduleResult): String = when (r) {
    is ScheduleResult.Scheduled ->
        if (r.conflicts.isEmpty()) stringResource(R.string.rec_scheduled) else stringResource(R.string.rec_scheduled_conflict, r.conflicts.size)
    is ScheduleResult.AlreadyScheduled -> stringResource(R.string.rec_already)
    is ScheduleResult.Invalid -> stringResource(if (r.reason == InvalidReason.ALREADY_ENDED) R.string.rec_ended else R.string.rec_too_long)
}

@Composable
private fun refreshStatus(r: EpgRefreshUi): String = when (r) {
    EpgRefreshUi.Idle -> ""
    is EpgRefreshUi.Running -> stringResource(R.string.epg_refreshing, r.programs)
    is EpgRefreshUi.Done -> when {
        r.result.noSource -> stringResource(R.string.epg_no_source)
        r.result.failedUrls.isNotEmpty() -> stringResource(R.string.epg_partial, r.result.programs, r.result.failedUrls.size)
        else -> stringResource(R.string.epg_updated, r.result.programs)
    }
    is EpgRefreshUi.Failed -> r.error.message.orEmpty()
}

@Composable
private fun timeRange(p: EpgProgram): String {
    val fmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    return fmt.format(Date(p.startTime)) + " – " + fmt.format(Date(p.endTime))
}

private fun initials(name: String): String =
    name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { "?" }

@Composable
private fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) { delay(30_000); value = System.currentTimeMillis() }
}
