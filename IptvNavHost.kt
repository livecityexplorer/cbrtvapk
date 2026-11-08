package app.iptv.player.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.*
import app.iptv.feature.epg.EpgScreen
import app.iptv.feature.livetv.LiveTvScreen
import app.iptv.feature.player.MultiViewScreen
import app.iptv.feature.player.PlayerScreen
import app.iptv.feature.player.VodPlayerScreen
import app.iptv.feature.recordings.RecordingsScreen
import app.iptv.feature.search.SearchScreen
import app.iptv.feature.series.SeriesDetailScreen
import app.iptv.feature.series.SeriesScreen
import app.iptv.feature.vod.MovieDetailScreen
import app.iptv.feature.vod.MoviesScreen
import app.iptv.feature.settings.SettingsScreen
import app.iptv.player.AppViewModel
import app.iptv.player.home.HomeScreen
import app.iptv.player.parental.PinGuard
import app.iptv.feature.settings.parental.ParentalLocksScreen
import app.iptv.feature.settings.playlists.PlaylistEditScreen
import app.iptv.feature.settings.playlists.PlaylistListScreen

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IptvNavHost(pendingChannelId: Long? = null, onPendingConsumed: () -> Unit = {}) {
    val nav = rememberNavController()
    // A tapped reminder notification asks us to open a channel.
    LaunchedEffect(pendingChannelId) {
        if (pendingChannelId != null) { nav.navigate(Route.player(pendingChannelId)); onPendingConsumed() }
    }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    // Highlight "Settings" while inside its sub-screens too.
    val selected = Route.topLevel.firstOrNull { route == it.path }
        ?: when (route) {
            Route.PLAYLISTS, Route.PLAYLIST_EDIT, Route.LOCKS -> Route.Settings
            Route.MULTIVIEW -> Route.MultiView
            Route.MOVIE_DETAIL -> Route.Movies
            Route.SERIES_DETAIL -> Route.Series
            else -> null
        }

    val showRail = route?.startsWith("player") != true && route?.startsWith("multiview") != true &&
        route?.startsWith("vodplayer") != true // full-screen while playing

    val appViewModel: AppViewModel = hiltViewModel()
    val recordingActive by appViewModel.recordingActive.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (showRail) Column(Modifier.width(200.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Route.topLevel.forEach { r ->
                Button(
                    onClick = { nav.navigate(r.path) { launchSingleTop = true; popUpTo(Route.Home.path) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(r.label + if (selected == r) " •" else "") }
            }
        }
        NavHost(nav, startDestination = Route.Home.path, modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Fallback for any top-level destination without a dedicated screen.
            Route.topLevel.filter { it !in setOf(Route.Home, Route.Settings, Route.Live, Route.Epg, Route.MultiView, Route.Movies, Route.Series, Route.Search, Route.Recordings) }.forEach { r ->
                composable(r.path) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(r.label, style = MaterialTheme.typography.displaySmall)
                    }
                }
            }
            composable(Route.Home.path) {
                HomeScreen(
                    onPlayChannel = { nav.navigate(Route.player(it)) },
                    onResume = { kind, id -> nav.navigate(Route.vodPlayer(kind, id)) },
                    onOpenMovie = { nav.navigate(Route.movieDetail(it)) },
                    onOpenSeries = { nav.navigate(Route.seriesDetail(it)) },
                    onAddPlaylist = { nav.navigate(Route.PLAYLISTS) }
                )
            }
            composable(Route.Recordings.path) {
                RecordingsScreen(onPlay = { nav.navigate(Route.vodPlayer("recording", it)) })
            }
            composable(Route.Search.path) {
                SearchScreen(
                    onPlayChannel = { nav.navigate(Route.player(it)) },
                    onOpenMovie = { nav.navigate(Route.movieDetail(it)) },
                    onOpenSeries = { nav.navigate(Route.seriesDetail(it)) },
                    onAddPlaylist = { nav.navigate(Route.PLAYLISTS) }
                )
            }
            composable(Route.Movies.path) {
                MoviesScreen(
                    onOpenMovie = { nav.navigate(Route.movieDetail(it)) },
                    onResumeMovie = { nav.navigate(Route.vodPlayer("movie", it)) },
                    onAddPlaylist = { nav.navigate(Route.PLAYLISTS) }
                )
            }
            composable(Route.MOVIE_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                MovieDetailScreen(onPlay = { id, resume -> nav.navigate(Route.vodPlayer("movie", id, resume)) })
            }
            composable(Route.Series.path) {
                SeriesScreen(
                    onOpenSeries = { nav.navigate(Route.seriesDetail(it)) },
                    onResumeEpisode = { nav.navigate(Route.vodPlayer("episode", it)) },
                    onAddPlaylist = { nav.navigate(Route.PLAYLISTS) }
                )
            }
            composable(Route.SERIES_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                SeriesDetailScreen(onPlayEpisode = { id, resume -> nav.navigate(Route.vodPlayer("episode", id, resume)) })
            }
            composable(
                Route.VOD_PLAYER,
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("id") { type = NavType.LongType },
                    navArgument("resume") { type = NavType.BoolType; defaultValue = true }
                )
            ) { entry ->
                val kind = entry.arguments?.getString("kind").orEmpty()
                val id = entry.arguments?.getLong("id") ?: -1L
                PinGuard(
                    key = "$kind/$id",
                    needsPin = { g -> when (kind) { "movie" -> g.movieNeedsPin(id); "episode" -> g.episodeNeedsPin(id); else -> false } },
                    onDenied = { nav.popBackStack() }
                ) { VodPlayerScreen(onBack = { nav.popBackStack() }) }
            }
            composable(Route.Epg.path) {
                EpgScreen(
                    onWatch = { id, start, end -> nav.navigate(Route.player(id, start, end)) },
                    onAddPlaylist = { nav.navigate(Route.PLAYLISTS) }
                )
            }
            composable(Route.Live.path) {
                LiveTvScreen(
                    onPlayChannel = { nav.navigate(Route.player(it)) },
                    onAddPlaylist = { nav.navigate(Route.PLAYLISTS) }
                )
            }
            composable(
                Route.PLAYER,
                arguments = listOf(
                    navArgument("channelId") { type = NavType.LongType },
                    navArgument("programStart") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("programEnd") { type = NavType.LongType; defaultValue = -1L }
                )
            ) { entry ->
                val channelId = entry.arguments?.getLong("channelId") ?: -1L
                PinGuard(key = channelId, needsPin = { g -> g.channelNeedsPin(channelId) }, onDenied = { nav.popBackStack() }) {
                    PlayerScreen(
                        onBack = { nav.popBackStack() },
                        // replace the player (its engine is released) instead of stacking a second set of streams on top
                        onOpenMultiView = { id -> nav.navigate(Route.multiView(id)) { popUpTo(Route.PLAYER) { inclusive = true } } }
                    )
                }
            }
            composable(
                Route.MULTIVIEW,
                arguments = listOf(navArgument("channelId") { type = NavType.LongType; defaultValue = -1L })
            ) {
                MultiViewScreen(onBack = { nav.popBackStack() })
            }
            composable(Route.Settings.path) {
                SettingsScreen(onOpenPlaylists = { nav.navigate(Route.PLAYLISTS) }, onOpenLocks = { nav.navigate(Route.LOCKS) })
            }
            composable(Route.LOCKS) {
                ParentalLocksScreen()
            }
            composable(Route.PLAYLISTS) {
                PlaylistListScreen(
                    onAdd = { nav.navigate(Route.playlistEdit(Route.NEW_PLAYLIST_ID)) },
                    onEdit = { nav.navigate(Route.playlistEdit(it)) }
                )
            }
            composable(
                Route.PLAYLIST_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = Route.NEW_PLAYLIST_ID })
            ) {
                PlaylistEditScreen(onClose = { nav.popBackStack() })
            }
        }
    }
        // global recording indicator (also visible in full-screen players)
        if (recordingActive) RecordingBadge(Modifier.align(Alignment.TopEnd).padding(16.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecordingBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(10.dp).background(Color(0xFFFF3B30), RoundedCornerShape(5.dp)))
        Text("REC", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}
