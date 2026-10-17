package com.jedrama.fork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.jedrama.fork.generation.GenerationScheduler
import com.jedrama.fork.generation.GenerationState
import com.jedrama.fork.generation.SeriesDraft
import com.jedrama.fork.generation.SeriesDraftStore
import com.jedrama.fork.library.Library
import com.jedrama.fork.library.PlaybackRepository
import com.jedrama.fork.library.SeriesRepository
import com.jedrama.fork.ui.DraftDetailScreen
import com.jedrama.fork.ui.DraftEpisodeScreen
import com.jedrama.fork.ui.DraftsScreen
import com.jedrama.fork.ui.FavoritesScreen
import com.jedrama.fork.ui.JeDramaTheme
import com.jedrama.fork.ui.Gold
import com.jedrama.fork.ui.PlayerScreen
import com.jedrama.fork.ui.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var seriesRepository: SeriesRepository

    @Inject
    lateinit var playbackRepository: PlaybackRepository

    @Inject
    lateinit var generationScheduler: GenerationScheduler

    private val navController = rememberNavController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JeDramaTheme {
                var selectedTab by remember { mutableStateOf(0) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Home, null) },
                                label = { Text(stringResource(R.string.nav_home)) },
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Favorite, null) },
                                label = { Text(stringResource(R.string.nav_favorites)) },
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, null) },
                                label = { Text(stringResource(R.string.nav_settings)) },
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                            )
                        }
                    },
                ) { padding ->
                    if (selectedTab == 0) {
                        NavHost(
                            navController = navController,
                            startDestination = "home",
                        ) {
                            composable("home") {
                                DraftsScreen(
                                    onOpen = { seriesId ->
                                        navController.navigate("detail/$seriesId")
                                    },
                                    modifier = Modifier.padding(padding),
                                )
                            }
                            composable("detail/{seriesId}") { backStackEntry ->
                                val seriesId = backStackEntry.arguments?.getString("seriesId") ?: ""
                                DraftDetailScreen(
                                    seriesId = seriesId,
                                    onPlay = { episodeNumber ->
                                        navController.navigate("player/$seriesId/$episodeNumber")
                                    },
                                    onDeleted = {
                                        navController.popBackStack()
                                    },
                                    modifier = Modifier.padding(padding),
                                )
                            }
                            composable("player/{seriesId}/{startEpisode}") { backStackEntry ->
                                val seriesId = backStackEntry.arguments?.getString("seriesId") ?: ""
                                val startEpisode = backStackEntry.arguments?.getInt("startEpisode") ?: 1
                                PlayerScreen(
                                    seriesId = seriesId,
                                    startEpisode = startEpisode,
                                    onBack = {
                                        navController.popBackStack()
                                    },
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    modifier = Modifier.padding(padding),
                                )
                            }
                        }
                    } else if (selectedTab == 1) {
                        FavoritesScreen(
                            onOpen = { seriesId ->
                                navController.navigate("detail/$seriesId")
                            },
                            modifier = Modifier.padding(padding),
                        )
                    } else {
                        SettingsScreen(
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}