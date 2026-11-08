package app.iptv.feature.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import app.iptv.core.ui.PinDialog
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.*
import app.iptv.core.domain.model.Category
import app.iptv.core.domain.model.Channel
import app.iptv.core.domain.model.EpgProgram
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import app.iptv.core.domain.repository.LiveViewMode
import coil.compose.SubcomposeAsyncImage
import androidx.compose.material3.Text as M3Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvScreen(
    onPlayChannel: (Long) -> Unit,
    onAddPlaylist: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val paged = viewModel.pagedChannels.collectAsLazyPagingItems()
    val static by viewModel.staticChannels.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val nowMinute by rememberMinuteClock()

    var actionChannel by remember { mutableStateOf<Channel?>(null) }
    var actionCategory by remember { mutableStateOf<Category?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    // Parental controls: a pending action that needs the PIN first, and a hint when locking without a PIN.
    var pinRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var noPinNotice by remember { mutableStateOf(false) }
    val hasPin by viewModel.hasPin.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun guarded(action: () -> Unit) { scope.launch { if (viewModel.needsUnlock()) pinRequest = action else action() } }
    // Which list had a focused channel when we left (e.g. for the player). Survives navigation/process death.
    var focusedListKey by rememberSaveable { mutableStateOf<String?>(null) }

    val active = ui.active
    when {
        ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        active == null -> NoPlaylist(onAddPlaylist)
        else -> {
            val sidebarFocus = remember { FocusRequester() }
            LaunchedEffect(active.id) {
                if (focusedListKey == null) try { sidebarFocus.requestFocus() } catch (e: Exception) { /* not attached yet */ }
            }
            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    ui = ui, selectedFocus = sidebarFocus,
                    onSelect = { sel ->
                        val apply = { viewModel.select(sel); focusedListKey = null }
                        if (sel is LiveSelection.Category) scope.launch { if (viewModel.categoryNeedsPin(sel.id)) pinRequest = apply else apply() } else apply()
                    },
                    onCategoryMenu = { actionCategory = it },
                    onToggleHidden = viewModel::toggleShowHidden
                )
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 24.dp)) {
                    Header(
                        ui = ui, active = active.name,
                        onQuery = { viewModel.setQuery(it); focusedListKey = null },
                        onToggleView = viewModel::toggleViewMode, onPickPlaylist = { showPicker = true }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(selectionTitle(ui), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    ChannelContent(
                        ui = ui, paged = paged, static = static, favoriteIds = favoriteIds, focusedListKey = focusedListKey,
                        onListFocused = { focusedListKey = it },
                        onPlay = { viewModel.onPlay(it); onPlayChannel(it.id) },
                        onMenu = { actionChannel = it },
                        nowMinute = nowMinute, nowNext = viewModel::nowNext
                    )
                }
            }
        }
    }

    actionChannel?.let { ch ->
        ChannelActionsDialog(
            channel = ch, favorite = ch.id in favoriteIds, canReorder = ui.canReorder,
            onFavorite = { viewModel.toggleFavorite(ch); actionChannel = null },
            onMove = { up -> viewModel.move(ch, up) }, // dialog stays open so the user can keep moving it
            locked = ch.isLocked,
            onToggleLock = { if (!hasPin) noPinNotice = true else guarded { viewModel.toggleChannelLock(ch); actionChannel = null } },
            onDismiss = { actionChannel = null }
        )
    }
    actionCategory?.let { cat ->
        AlertDialog(
            onDismissRequest = { actionCategory = null },
            title = { M3Text(cat.name) },
            text = {
                Column {
                    TextButton(onClick = { viewModel.toggleCategoryHidden(cat); actionCategory = null }) {
                        M3Text(stringResource(if (cat.isHidden) R.string.action_show_category else R.string.action_hide_category))
                    }
                    TextButton(onClick = { if (!hasPin) noPinNotice = true else guarded { viewModel.toggleCategoryLock(cat) }; actionCategory = null }) {
                        M3Text(stringResource(if (cat.isLocked) R.string.action_unlock_category else R.string.action_lock_category))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionCategory = null }) { M3Text(stringResource(R.string.action_close)) } }
        )
    }
    pinRequest?.let { granted ->
        PinDialog(
            title = stringResource(R.string.pin_required), onSubmit = viewModel::verifyPin,
            onSuccess = { pinRequest = null; granted() }, onDismiss = { pinRequest = null }
        )
    }
    if (noPinNotice) {
        AlertDialog(
            onDismissRequest = { noPinNotice = false },
            title = { M3Text(stringResource(R.string.need_pin_title)) },
            text = { M3Text(stringResource(R.string.need_pin_message)) },
            confirmButton = { TextButton(onClick = { noPinNotice = false }) { M3Text(stringResource(R.string.action_close)) } }
        )
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { M3Text(stringResource(R.string.choose_playlist)) },
            text = {
                Column {
                    ui.playlists.forEach { p ->
                        TextButton(onClick = { viewModel.setActivePlaylist(p.id); showPicker = false }) {
                            M3Text(if (p.id == ui.active?.id) "• ${p.name}" else p.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { M3Text(stringResource(R.string.action_close)) } }
        )
    }
}

// ---------------------------------------------------------------- sidebar

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Sidebar(
    ui: LiveUiState, selectedFocus: FocusRequester,
    onSelect: (LiveSelection) -> Unit, onCategoryMenu: (Category) -> Unit, onToggleHidden: () -> Unit
) {
    val visible = ui.categories.filterNot { it.isHidden }
    val hidden = ui.categories.filter { it.isHidden }
    LazyColumn(
        Modifier.width(280.dp).fillMaxHeight().padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            SidebarItem(stringResource(R.string.sel_all), ui.selection == LiveSelection.All, false, { onSelect(LiveSelection.All) }, null,
                Modifier.focusWhen(ui.selection == LiveSelection.All, selectedFocus))
        }
        item {
            SidebarItem(stringResource(R.string.sel_favorites), ui.selection == LiveSelection.Favorites, false, { onSelect(LiveSelection.Favorites) }, null,
                Modifier.focusWhen(ui.selection == LiveSelection.Favorites, selectedFocus))
        }
        item {
            SidebarItem(stringResource(R.string.sel_recent), ui.selection == LiveSelection.Recent, false, { onSelect(LiveSelection.Recent) }, null,
                Modifier.focusWhen(ui.selection == LiveSelection.Recent, selectedFocus))
        }
        items(visible, key = { it.id }) { c ->
            val selected = ui.selection == LiveSelection.Category(c.id)
            SidebarItem((if (c.isLocked) "🔒 " else "") + c.name, selected, false, { onSelect(LiveSelection.Category(c.id)) }, { onCategoryMenu(c) },
                Modifier.focusWhen(selected, selectedFocus))
        }
        if (hidden.isNotEmpty()) {
            item {
                SidebarItem(
                    stringResource(if (ui.showHidden) R.string.hide_hidden else R.string.show_hidden, hidden.size),
                    selected = false, dimmed = true, onClick = onToggleHidden, onLongClick = null
                )
            }
            if (ui.showHidden) {
                items(hidden, key = { -it.id }) { c ->
                    // Hidden categories can only be un-hidden here (their channels are excluded everywhere).
                    SidebarItem(stringResource(R.string.hidden_suffix, c.name), false, true, { onCategoryMenu(c) }, { onCategoryMenu(c) })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SidebarItem(
    label: String, selected: Boolean, dimmed: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onMenuKey(onLongClick),
        colors = CardDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
    ) {
        Text(
            label, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
        )
    }
}

// ---------------------------------------------------------------- header

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Header(ui: LiveUiState, active: String, onQuery: (String) -> Unit, onToggleView: () -> Unit, onPickPlaylist: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.playlists.size > 1) Button(onClick = onPickPlaylist) { Text(active, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        else Text(active, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(
            value = ui.query, onValueChange = onQuery, singleLine = true, modifier = Modifier.weight(1f),
            label = { M3Text(stringResource(R.string.search_channels)) }
        )
        Button(onClick = onToggleView) {
            Text(stringResource(if (ui.viewMode == LiveViewMode.LIST) R.string.view_grid else R.string.view_list))
        }
    }
}

@Composable
private fun selectionTitle(ui: LiveUiState): String = when (val s = ui.selection) {
    LiveSelection.All -> stringResource(R.string.sel_all)
    LiveSelection.Favorites -> stringResource(R.string.sel_favorites)
    LiveSelection.Recent -> stringResource(R.string.sel_recent)
    is LiveSelection.Category -> ui.categories.firstOrNull { it.id == s.id }?.name ?: stringResource(R.string.sel_all)
}

// ---------------------------------------------------------------- content

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelContent(
    ui: LiveUiState, paged: LazyPagingItems<Channel>, static: List<Channel>?, favoriteIds: Set<Long>,
    focusedListKey: String?, onListFocused: (String) -> Unit, onPlay: (Channel) -> Unit, onMenu: (Channel) -> Unit,
    nowMinute: Long, nowNext: (Channel, Long) -> Flow<List<EpgProgram>>
) {
    val listKey = "${ui.selection.encode()}|${ui.query}|${ui.viewMode}|${ui.active?.id}"
    // New key = new list: scroll position and restore index start fresh.
    key(listKey) {
        var lastIndex by rememberSaveable { mutableIntStateOf(0) }
        val shouldRestore = focusedListKey == listKey
        val onFocused: (Int) -> Unit = { lastIndex = it; onListFocused(listKey) }

        when (ui.selection) {
            LiveSelection.Favorites, LiveSelection.Recent -> when {
                static == null -> CenteredLoading()
                static.isEmpty() -> EmptyState(emptyMessage(ui))
                else -> ChannelCollection(
                    count = static.size, viewMode = ui.viewMode, channelAt = { static.getOrNull(it) }, keyAt = { static[it].id },
                    favoriteIds = favoriteIds, restoreIndex = lastIndex, shouldRestore = shouldRestore,
                    onFocused = onFocused, onPlay = onPlay, onMenu = onMenu, nowMinute = nowMinute, nowNext = nowNext
                )
            }
            else -> {
                val refresh = paged.loadState.refresh
                when {
                    refresh is LoadState.Error && paged.itemCount == 0 ->
                        ErrorState(refresh.error.message.orEmpty(), onRetry = { paged.retry() })
                    refresh is LoadState.Loading && paged.itemCount == 0 -> CenteredLoading()
                    paged.itemCount == 0 -> EmptyState(emptyMessage(ui))
                    else -> ChannelCollection(
                        count = paged.itemCount, viewMode = ui.viewMode, channelAt = { paged[it] }, keyAt = paged.itemKey { it.id },
                        favoriteIds = favoriteIds, restoreIndex = lastIndex, shouldRestore = shouldRestore,
                        onFocused = onFocused, onPlay = onPlay, onMenu = onMenu, nowMinute = nowMinute, nowNext = nowNext
                    )
                }
            }
        }
    }
}

@Composable
private fun emptyMessage(ui: LiveUiState): String = when {
    ui.query.isNotBlank() -> stringResource(R.string.empty_no_match, ui.query.trim())
    ui.selection == LiveSelection.Favorites -> stringResource(R.string.empty_favorites)
    ui.selection == LiveSelection.Recent -> stringResource(R.string.empty_recent)
    else -> stringResource(R.string.empty_category)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelCollection(
    count: Int, viewMode: LiveViewMode, channelAt: (Int) -> Channel?, keyAt: ((Int) -> Any)?,
    favoriteIds: Set<Long>, restoreIndex: Int, shouldRestore: Boolean,
    onFocused: (Int) -> Unit, onPlay: (Channel) -> Unit, onMenu: (Channel) -> Unit,
    nowMinute: Long, nowNext: (Channel, Long) -> Flow<List<EpgProgram>>
) {
    val restorer = remember { FocusRequester() }
    var restored by remember { mutableStateOf(false) }
    // Focus the channel that was focused before leaving (once), after the list has items.
    LaunchedEffect(count, shouldRestore) {
        if (!restored && shouldRestore && restoreIndex < count) {
            val ok = try { restorer.requestFocus(); true } catch (e: Exception) { false }
            if (ok) restored = true
        }
    }

    fun itemModifier(index: Int): Modifier {
        var m: Modifier = Modifier
        if (index == restoreIndex) m = m.focusRequester(restorer)
        return m.onFocusChanged { if (it.hasFocus) onFocused(index) }
    }

    if (viewMode == LiveViewMode.LIST) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(count, key = keyAt) { index ->
                val ch = channelAt(index) ?: return@items
                ChannelRow(ch, ch.id in favoriteIds, itemModifier(index), nowMinute, nowNext, { onPlay(ch) }, { onMenu(ch) })
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(180.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(count, key = keyAt) { index ->
                val ch = channelAt(index) ?: return@items
                ChannelTile(ch, ch.id in favoriteIds, itemModifier(index), { onPlay(ch) }, { onMenu(ch) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(
    ch: Channel, favorite: Boolean, modifier: Modifier, nowMinute: Long,
    nowNext: (Channel, Long) -> Flow<List<EpgProgram>>, onClick: () -> Unit, onMenu: () -> Unit
) {
    Card(onClick = onClick, onLongClick = onMenu, modifier = modifier.fillMaxWidth().onMenuKey(onMenu)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (ch.number > 0) {
                Text(ch.number.toString(), Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ChannelLogo(ch.logoUrl, ch.name, Modifier.size(width = 72.dp, height = 44.dp))
            val programs by remember(ch.id, nowMinute) { nowNext(ch, nowMinute) }.collectAsState(initial = emptyList())
            val current = programs.firstOrNull { it.isLiveAt(nowMinute) }
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current != null) {
                    Text(current.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgramProgress(current.progressAt(nowMinute))
                }
            }
            if (favorite) Text("★", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelTile(ch: Channel, favorite: Boolean, modifier: Modifier, onClick: () -> Unit, onMenu: () -> Unit) {
    Card(onClick = onClick, onLongClick = onMenu, modifier = modifier.onMenuKey(onMenu)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ChannelLogo(ch.logoUrl, ch.name, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            Spacer(Modifier.height(8.dp))
            Text(
                (if (favorite) "★ " else "") + ch.name,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelLogo(url: String?, name: String, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNullOrBlank()) Initials(name)
        else SubcomposeAsyncImage(
            model = url, contentDescription = name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Initials(name) } },
            error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Initials(name) } }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Initials(name: String) {
    val text = remember(name) {
        name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { "?" }
    }
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------------------------------------------------------- states & dialogs

@Composable
private fun CenteredLoading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyState(message: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(message, style = MaterialTheme.typography.bodyLarge)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorState(detail: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) { } }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.load_error), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.retry)) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NoPlaylist(onAdd: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) { } }
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.no_playlist_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.no_playlist_message), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.add_playlist)) }
    }
}

@Composable
private fun ChannelActionsDialog(
    channel: Channel, favorite: Boolean, canReorder: Boolean,
    onFavorite: () -> Unit, onMove: (up: Boolean) -> Unit, locked: Boolean, onToggleLock: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text(channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                TextButton(onClick = onFavorite) {
                    M3Text(stringResource(if (favorite) R.string.action_remove_favorite else R.string.action_add_favorite))
                }
                TextButton(onClick = onToggleLock) {
                    M3Text(stringResource(if (locked) R.string.action_unlock_channel else R.string.action_lock_channel))
                }
                if (canReorder) {
                    TextButton(onClick = { onMove(true) }) { M3Text(stringResource(R.string.action_move_up)) }
                    TextButton(onClick = { onMove(false) }) { M3Text(stringResource(R.string.action_move_down)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { M3Text(stringResource(R.string.action_close)) } }
    )
}

// ---------------------------------------------------------------- helpers

private fun Modifier.focusWhen(condition: Boolean, requester: FocusRequester): Modifier =
    if (condition) focusRequester(requester) else this

/** The remote's Menu key opens the same actions as a long press on OK. */
private fun Modifier.onMenuKey(action: (() -> Unit)?): Modifier =
    if (action == null) this else onKeyEvent { e ->
        if (e.type == KeyEventType.KeyUp && e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU) { action(); true } else false
    }

@Composable
private fun ProgramProgress(fraction: Float) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color(0xFF4F8CFF)))
    }
}

/** Epoch millis truncated to the minute; ticks once a minute so "now playing" stays current. */
@Composable
private fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis() / 60_000 * 60_000) {
    while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000)
        value = System.currentTimeMillis() / 60_000 * 60_000
    }
}
