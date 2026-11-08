@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iptv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptv.core.domain.model.AspectMode
import app.iptv.core.domain.model.Channel
import app.iptv.core.domain.util.LayoutRect
import app.iptv.core.domain.util.MultiViewLayout
import app.iptv.feature.player.engine.EngineStatus
import kotlin.math.roundToInt
import androidx.compose.material3.Text as M3Text

private sealed interface PickerTarget {
    data object Add : PickerTarget
    data class Replace(val paneId: Int) : PickerTarget
}

/**
 * 2-4 live channels at once. D-pad moves between panes (the focused one is the only one with sound),
 * OK expands a pane to full screen, long-press / Menu opens its options. Back leaves full screen, then the screen.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MultiViewScreen(onBack: () -> Unit, viewModel: MultiViewViewModel = hiltViewModel()) {
    val panes by viewModel.panes.collectAsStateWithLifecycle()
    val focusedId by viewModel.focusedId.collectAsStateWithLifecycle()
    val expandedId by viewModel.expandedId.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var menuPaneId by remember { mutableStateOf<Int?>(null) }

    ImmersiveWhilePlaying()
    LifecycleCallbacks(onStop = viewModel::onBackgrounded, onStart = viewModel::onForegrounded)
    BackHandler(enabled = expandedId != null) { expandedId?.let { viewModel.toggleExpand(it) } }
    // Opened from the menu without a channel: start with the picker.
    LaunchedEffect(loaded, panes.isEmpty()) { if (loaded && panes.isEmpty() && picker == null) picker = PickerTarget.Add }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val gapPx = with(density) { 6.dp.toPx() }
        val canAdd = panes.size < MultiViewLayout.MAX_PANES
        val expanded = expandedId?.let { id -> panes.firstOrNull { it.id == id } }

        if (expanded != null) {
            // Other panes keep playing (muted, surface detached) so returning to the grid is instant.
            androidx.compose.runtime.key(expanded.id) {
                PaneView(expanded, Modifier.fillMaxSize(), focused = true, requestFocus = true,
                    onFocused = {}, onExpand = { viewModel.toggleExpand(expanded.id) }, onMenu = { menuPaneId = expanded.id })
            }
        } else {
            val slots = MultiViewLayout.compute(panes.size + if (canAdd) 1 else 0, wPx, hPx, gapPx)
            panes.forEachIndexed { i, pane ->
                androidx.compose.runtime.key(pane.id) {
                    PaneView(pane, Modifier.slot(slots[i], density), focused = focusedId == pane.id, requestFocus = i == 0,
                        onFocused = { viewModel.setFocus(pane.id) }, onExpand = { viewModel.toggleExpand(pane.id) }, onMenu = { menuPaneId = pane.id })
                }
            }
            if (canAdd && slots.size > panes.size) AddSlot(Modifier.slot(slots[panes.size], density)) { picker = PickerTarget.Add }
        }
    }

    menuPaneId?.let { id ->
        panes.firstOrNull { it.id == id }?.let { pane ->
            val channel by pane.player.channel.collectAsStateWithLifecycle()
            AlertDialog(
                onDismissRequest = { menuPaneId = null },
                title = { M3Text(channel?.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        TextButton(onClick = { picker = PickerTarget.Replace(id); menuPaneId = null }) { M3Text(stringResource(R.string.mv_change_channel)) }
                        TextButton(onClick = { viewModel.toggleExpand(id); menuPaneId = null }) {
                            M3Text(stringResource(if (expandedId == id) R.string.mv_back_to_grid else R.string.mv_fullscreen))
                        }
                        TextButton(onClick = { viewModel.remove(id); menuPaneId = null }) { M3Text(stringResource(R.string.mv_remove)) }
                    }
                },
                confirmButton = { TextButton(onClick = { menuPaneId = null }) { M3Text(stringResource(R.string.menu_close)) } }
            )
        }
    }

    picker?.let { target ->
        ChannelPicker(
            viewModel = viewModel,
            onPick = { ch ->
                when (target) {
                    PickerTarget.Add -> viewModel.add(ch)
                    is PickerTarget.Replace -> viewModel.replace(target.paneId, ch)
                }
                picker = null
            },
            onDismiss = { picker = null; if (panes.isEmpty()) onBack() }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PaneView(
    pane: Pane, modifier: Modifier, focused: Boolean, requestFocus: Boolean,
    onFocused: () -> Unit, onExpand: () -> Unit, onMenu: () -> Unit
) {
    val status by pane.player.status.collectAsStateWithLifecycle()
    val engine by pane.engine.state.collectAsStateWithLifecycle()
    val channel by pane.player.channel.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestFocus) { if (requestFocus) try { focus.requestFocus() } catch (e: Exception) { /* not attached yet */ } }

    Box(
        modifier
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false }
            .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x44FFFFFF))
            .combinedClickable(
                onClick = { if (status is PlayerStatus.Failed) pane.player.retry() else onExpand() },
                onLongClick = onMenu
            )
    ) {
        VideoSurface(pane.engine, engine, AspectMode.FIT)

        val busy = status is PlayerStatus.Loading || status is PlayerStatus.Reconnecting || engine.status == EngineStatus.BUFFERING
        if (status is PlayerStatus.Failed) {
            Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.mv_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                Text((status as PlayerStatus.Failed).message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.mv_failed_hint), style = MaterialTheme.typography.bodySmall)
            }
        } else if (busy) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                if (status is PlayerStatus.Reconnecting) Text(stringResource(R.string.mv_reconnecting), style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(
            Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            if (focused) Text("🔊", style = MaterialTheme.typography.bodySmall)
            Text(channel?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AddSlot(modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x44FFFFFF))
            .background(Color(0xFF10151F))
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+", style = MaterialTheme.typography.displayMedium)
            Text(stringResource(R.string.mv_add_slot), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.mv_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChannelPicker(viewModel: MultiViewViewModel, onPick: (Channel) -> Unit, onDismiss: () -> Unit) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val items = viewModel.pickerChannels.collectAsLazyPagingItems()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text(stringResource(R.string.mv_pick_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = viewModel::setQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { M3Text(stringResource(R.string.mv_search)) }
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(300.dp)) {
                    items(items.itemCount, key = items.itemKey { it.id }) { i ->
                        val ch = items[i] ?: return@items
                        TextButton(onClick = { onPick(ch) }, modifier = Modifier.fillMaxWidth()) {
                            M3Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (items.itemCount == 0 && items.loadState.refresh !is LoadState.Loading) M3Text(stringResource(R.string.mv_no_channels))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { M3Text(stringResource(R.string.mv_cancel)) } }
    )
}

private fun Modifier.slot(r: LayoutRect, density: androidx.compose.ui.unit.Density): Modifier =
    this.offset { IntOffset(r.x.roundToInt(), r.y.roundToInt()) }
        .size(with(density) { r.w.toDp() }, with(density) { r.h.toDp() })
