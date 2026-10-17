package com.jedrama.fork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jedrama.fork.library.PlaybackRepository
import com.jedrama.fork.library.SeriesRepository
import com.jedrama.fork.ui.ForYouScreen
import com.jedrama.fork.ui.MyListScreen
import com.jedrama.fork.ui.PlayerScreen
import com.jedrama.fork.ui.ProfileScreen
import com.jedrama.fork.ui.SeriesDetailScreen
import com.jedrama.fork.ui.SettingsScreen
import com.jedrama.fork.ui.TheaterScreen
import com.jedrama.fork.ui.JeDramaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Onglets de la barre principale. */
private enum class MainTab(val route: String, val icon: ImageVector, val labelRes: Int) {
    ForYou("for_you", Icons.Filled.Home, R.string.nav_for_you),
    Theater("theater", Icons.Filled.VideoLibrary, R.string.nav_theater),
    MyList("my_list", Icons.Filled.Bookmark, R.string.nav_my_list),
    Profile("profile", Icons.Filled.Person, R.string.nav_profile),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var seriesRepository: SeriesRepository

    @Inject
    lateinit var playbackRepository: PlaybackRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JeDramaTheme {
                AppNav(
                    seriesRepository = seriesRepository,
                    playbackRepository = playbackRepository,
                )
            }
        }
    }
}

@Composable
private fun AppNav(
    seriesRepository: SeriesRepository,
    playbackRepository: PlaybackRepository,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // La barre disparaît sur le lecteur plein écran.
            if (currentRoute != "player/{seriesId}/{startEpisode}") {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, null) },
                            label = { Text(stringResource(tab.labelRes)) },
                            selected = currentRoute == tab.route ||
                                (tab == MainTab.ForYou && currentRoute == "series"),
                            onClick = { navController.navigateToTab(tab.route) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MainTab.ForYou.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(MainTab.ForYou.route) {
                ForYouScreen(
                    seriesRepository = seriesRepository,
                    playbackRepository = playbackRepository,
                    onOpenSeries = { id -> navController.navigate("series/$id") },
                )
            }
            composable(MainTab.Theater.route) {
                TheaterScreen(
                    seriesRepository = seriesRepository,
                    playbackRepository = playbackRepository,
                    onOpenSeries = { id -> navController.navigate("series/$id") },
                    onPlaySeries = { id, ep -> navController.navigate("player/$id/$ep") },
                )
            }
            composable(MainTab.MyList.route) {
                MyListScreen(
                    seriesRepository = seriesRepository,
                    playbackRepository = playbackRepository,
                    onOpenSeries = { id -> navController.navigate("series/$id") },
                    onPlaySeries = { id, ep -> navController.navigate("player/$id/$ep") },
                )
            }
            composable(MainTab.Profile.route) {
                ProfileScreen(
                    seriesRepository = seriesRepository,
                    playbackRepository = playbackRepository,
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("series/{seriesId}") { entry ->
                val seriesId = entry.arguments?.getString("seriesId") ?: ""
                SeriesDetailScreen(
                    seriesId = seriesId,
                    seriesRepository = seriesRepository,
                    playbackRepository = playbackRepository,
                    onPlay = { ep -> navController.navigate("player/$seriesId/$ep") },
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable("player/{seriesId}/{startEpisode}") { entry ->
                val seriesId = entry.arguments?.getString("seriesId") ?: ""
                val startEpisode = entry.arguments?.getInt("startEpisode") ?: 1
                PlayerScreen(
                    seriesId = seriesId,
                    startEpisode = startEpisode,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}

/**
 * Navigation entre onglets : un seul exemplaire de chaque destination,
 * retour au tab précédent restauré (états de défilement préservés).
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

