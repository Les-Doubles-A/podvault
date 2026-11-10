package com.podvault.app.navigation

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.podvault.app.R
import com.podvault.app.ui.components.LocalNowPlaying
import com.podvault.app.ui.components.NowPlaying
import com.podvault.app.ui.queue.QueueScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.podvault.app.PodVaultApplication
import com.podvault.app.ui.bookmarks.BookmarksScreen
import com.podvault.app.ui.components.MiniPlayer
import com.podvault.app.ui.home.HomeScreen
import com.podvault.app.ui.library.LibraryScreen
import com.podvault.app.ui.library.PodcastDetailScreen
import com.podvault.app.ui.library.DuplicatesScreen
import com.podvault.app.ui.player.PlayerScreen
import com.podvault.app.ui.search.SearchScreen
import com.podvault.app.ui.settings.OnboardingScreen
import com.podvault.app.ui.settings.SettingsScreen
import com.podvault.app.ui.settings.StatisticsScreen

@Composable
fun PodVaultApp() {
    val context = LocalContext.current
    val app = context.applicationContext as PodVaultApplication
    // null tant que DataStore n'a pas répondu : évite d'afficher l'accueil de bienvenue
    // une fraction de seconde à chaque lancement pour un utilisateur déjà initialisé.
    val hasOnboarded by app.preferencesManager.hasOnboarded.collectAsStateWithLifecycle(initialValue = null)
    if (hasOnboarded == null) return
    if (hasOnboarded == false) {
        OnboardingScreen(onFinished = { /* recomposition follows the DataStore flow */ })
        return
    }

    val navController = rememberNavController()
    val playbackState by app.playbackController.state.collectAsStateWithLifecycle()
    val queue by app.playbackController.queue.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }
    val queueActionLabel = stringResource(R.string.queue_view)

    // Indicateur "en lecture" et état "en file" diffusés à toutes les listes.
    val nowPlaying = remember(playbackState.episodeId, playbackState.podcastId, playbackState.isPlaying, queue.upNextIds) {
        NowPlaying(playbackState.episodeId, playbackState.podcastId, playbackState.isPlaying, queue.upNextIds)
    }

    // Retours de la file d'attente (ajout, doublon, lecture ensuite) avec raccourci vers la file.
    LaunchedEffect(Unit) {
        app.playbackController.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = if (event.offerQueueShortcut) queueActionLabel else null,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                navController.navigate(DetailRoutes.QUEUE) { launchSingleTop = true }
            }
        }
    }

    // Le lecteur plein écran affiche déjà ses propres commandes.
    val showMiniPlayer = currentRoute?.startsWith("player/") != true

    CompositionLocalProvider(LocalNowPlaying provides nowPlaying) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                if (showMiniPlayer) {
                    MiniPlayer(
                        state = playbackState,
                        progress = app.playbackController.progress,
                        onClick = {
                            playbackState.episodeId?.let {
                                navController.navigate(DetailRoutes.player(it))
                            }
                        },
                        onTogglePlayPause = { app.playbackController.togglePlayPause() }
                    )
                }
                BottomNavBar(navController)
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.Home.route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable(TopDestination.Home.route) {
                HomeScreen(
                    onEpisodeClick = { navController.navigate(DetailRoutes.player(it)) },
                    onGoToSettings = { navController.navigate(TopDestination.Settings.route) },
                    onOpenQueue = { navController.navigate(DetailRoutes.QUEUE) { launchSingleTop = true } }
                )
            }
            composable(TopDestination.Library.route) {
                LibraryScreen(
                    onPodcastClick = { navController.navigate(DetailRoutes.podcast(it)) },
                    onDuplicatesClick = { navController.navigate(DetailRoutes.DUPLICATES) }
                )
            }
            composable(DetailRoutes.DUPLICATES) {
                DuplicatesScreen(onBack = { navController.popBackStack() }, onEpisodeClick = { navController.navigate(DetailRoutes.player(it)) })
            }
            composable(TopDestination.Bookmarks.route) {
                BookmarksScreen(onOpenEpisodeAt = { episodeId, positionMs ->
                    navController.navigate(DetailRoutes.player(episodeId, positionMs))
                })
            }
            composable(TopDestination.Search.route) {
                SearchScreen(
                    onEpisodeClick = { navController.navigate(DetailRoutes.player(it)) },
                    onPodcastClick = { navController.navigate(DetailRoutes.podcast(it)) },
                    onBookmarkClick = { episodeId, position -> navController.navigate(DetailRoutes.player(episodeId, position)) }
                )
            }
            composable(TopDestination.Settings.route) {
                SettingsScreen(onOpenStatistics = { navController.navigate(DetailRoutes.STATISTICS) })
            }
            composable(DetailRoutes.QUEUE) { QueueScreen(onBack = { navController.popBackStack() }) }
            composable(DetailRoutes.STATISTICS) { StatisticsScreen(onBack = { navController.popBackStack() }) }
            composable(
                DetailRoutes.PODCAST,
                arguments = listOf(navArgument("podcastId") { type = NavType.LongType })
            ) { backStackEntry ->
                val podcastId = backStackEntry.arguments?.getLong("podcastId") ?: return@composable
                PodcastDetailScreen(
                    podcastId = podcastId,
                    onBack = { navController.popBackStack() },
                    onEpisodeClick = { navController.navigate(DetailRoutes.player(it)) }
                )
            }
            composable(
                DetailRoutes.PLAYER,
                arguments = listOf(
                    navArgument("episodeId") { type = NavType.LongType },
                    navArgument("positionMs") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val episodeId = backStackEntry.arguments?.getLong("episodeId") ?: return@composable
                val positionMs = backStackEntry.arguments?.getString("positionMs")?.toLongOrNull()
                PlayerScreen(
                    episodeId = episodeId,
                    initialPositionMs = positionMs,
                    onBack = { navController.popBackStack() },
                    onOpenQueue = { navController.navigate(DetailRoutes.QUEUE) { launchSingleTop = true } }
                )
            }
        }
    }
    }
}

/**
 * Barre d'onglets épurée : icônes seules, l'onglet actif s'étire en pastille avec son libellé.
 */
@Composable
private fun BottomNavBar(navController: androidx.navigation.NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val items = listOf(
        TopDestination.Home to Icons.Filled.Home,
        TopDestination.Library to Icons.Filled.LibraryMusic,
        TopDestination.Bookmarks to Icons.Filled.Bookmark,
        TopDestination.Search to Icons.Filled.Search,
        TopDestination.Settings to Icons.Filled.Settings
    )
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(60.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (destination, icon) ->
                val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "tabTint"
                )
                val background by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    label = "tabBackground"
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(background)
                        .clickable(role = Role.Tab) {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        .padding(horizontal = if (selected) 16.dp else 12.dp, vertical = 10.dp)
                        .animateContentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = destination.label, tint = tint, modifier = Modifier.size(24.dp))
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Text(destination.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}
