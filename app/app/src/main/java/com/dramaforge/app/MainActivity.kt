package com.dramaforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.dramaforge.app.catalog.Badge
import com.dramaforge.app.catalog.Episode
import com.dramaforge.app.catalog.SampleCatalog
import com.dramaforge.app.catalog.Series
import com.dramaforge.app.ui.DraftDetailScreen
import com.dramaforge.app.ui.DraftEpisodeScreen
import com.dramaforge.app.ui.DraftsScreen
import com.dramaforge.app.ui.DramaForgeTheme
import com.dramaforge.app.ui.Gold
import com.dramaforge.app.ui.Ink
import com.dramaforge.app.ui.SettingsScreen

/**
 * Coque de navigation en PERSONAL_MODE : tout episode est jouable, il n'existe
 * aucun ecran de monetisation.
 *
 * Trois sections : le catalogue (Accueil), les scripts generes sur l'appareil
 * (Brouillons) et la configuration de la generation (Parametres).
 *
 * Restent a implementer : StaticCatalogRepository + cache Room, et le lecteur
 * vertical Media3 pagine.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { DramaForgeApp() }
    }
}

private enum class Section(val labelRes: Int) {
    HOME(R.string.nav_home),
    DRAFTS(R.string.nav_drafts),
    SETTINGS(R.string.nav_settings),
}

@Composable
fun DramaForgeApp() {
    val nav = rememberNavController()
    DramaForgeTheme {
        Surface(color = Ink) {
            NavHost(nav, startDestination = "main") {
                composable("main") {
                    MainShell(
                        onOpenSeries = { nav.navigate("detail/${it.id}") },
                        onOpenDraft = { nav.navigate("draft/$it") },
                    )
                }
                composable("detail/{seriesId}") { entry ->
                    val id = entry.arguments?.getString("seriesId")
                    val series = SampleCatalog.series.firstOrNull { it.id == id }
                    if (series == null) {
                        nav.popBackStack()
                    } else {
                        DetailScreen(
                            series = series,
                            onBack = { nav.popBackStack() },
                            onPlay = { nav.navigate("player/${series.id}/${it.number}") },
                        )
                    }
                }
                composable("player/{seriesId}/{episode}") { entry ->
                    val id = entry.arguments?.getString("seriesId")
                    val number = entry.arguments?.getString("episode")?.toIntOrNull() ?: 1
                    val series = SampleCatalog.series.firstOrNull { it.id == id }
                    PlayerScreen(series, number) { nav.popBackStack() }
                }
                composable("draft/{seriesId}") { entry ->
                    val id = entry.arguments?.getString("seriesId").orEmpty()
                    TitledScreen(onBack = { nav.popBackStack() }) { padding ->
                        DraftDetailScreen(
                            seriesId = id,
                            onOpenEpisode = { nav.navigate("draft/$id/$it") },
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
                composable("draft/{seriesId}/{episode}") { entry ->
                    val id = entry.arguments?.getString("seriesId").orEmpty()
                    val number = entry.arguments?.getString("episode")?.toIntOrNull() ?: 1
                    TitledScreen(onBack = { nav.popBackStack() }) { padding ->
                        DraftEpisodeScreen(
                            seriesId = id,
                            episodeNumber = number,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainShell(onOpenSeries: (Series) -> Unit, onOpenDraft: (String) -> Unit) {
    var section by remember { mutableStateOf(Section.HOME) }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar {
                Section.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = section == entry,
                        onClick = { section = entry },
                        icon = {
                            Icon(
                                when (entry) {
                                    Section.HOME -> Icons.Default.Home
                                    Section.DRAFTS -> Icons.Default.Description
                                    Section.SETTINGS -> Icons.Default.Settings
                                },
                                null,
                            )
                        },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        when (section) {
            Section.HOME -> HomeContent(Modifier.padding(padding), onOpenSeries)
            Section.DRAFTS -> DraftsScreen(onOpen = onOpenDraft, modifier = Modifier.padding(padding))
            Section.SETTINGS -> SettingsScreen(Modifier.padding(padding))
        }
    }
}

private val homeTabs = listOf(
    R.string.home_tab_trending,
    R.string.home_tab_ranking,
    R.string.home_tab_fresh,
    R.string.home_tab_categories,
)

@Composable
private fun HomeContent(modifier: Modifier, onOpen: (Series) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab, containerColor = Ink) {
            homeTabs.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(label)) },
                )
            }
        }
        // La serie de la semaine arrive en premier : le catalogue est deja trie
        // du plus recent au plus ancien.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(SampleCatalog.series, key = { it.id }) { series ->
                SeriesCard(series, onOpen)
            }
        }
    }
}

@Composable
private fun SeriesCard(series: Series, onOpen: (Series) -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.clickable { onOpen(series) }) {
        Box {
            AsyncImage(
                model = series.coverUrl,
                contentDescription = series.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.5625f)
                    .clip(RoundedCornerShape(16.dp)),
            )
            series.badges.firstOrNull()?.let { badge ->
                BadgeChip(badge, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                series.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
            Text(
                pluralStringResource(
                    R.plurals.episode_count,
                    series.episodes.size,
                    series.episodes.size,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BadgeChip(badge: Badge, modifier: Modifier = Modifier) {
    val label = when (badge) {
        Badge.NEW -> R.string.badge_new
        Badge.EXCLUSIVE -> R.string.badge_exclusive
    }
    Surface(color = Gold, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            stringResource(label),
            color = Ink,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Echafaudage commun aux ecrans de brouillon : une barre avec un retour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitledScreen(
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(series: Series, onBack: () -> Unit, onPlay: (Episode) -> Unit) {
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text(series.title) },
                navigationIcon = {
                    IconButton(onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(series.logline, style = MaterialTheme.typography.bodyMedium) }
            item {
                Text(
                    series.genres.joinToString(" · "),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            item {
                Button(
                    onClick = { series.episodes.firstOrNull()?.let(onPlay) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(stringResource(R.string.detail_play_first))
                }
            }
            item {
                Text(
                    stringResource(R.string.detail_episodes),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // PERSONAL_MODE : aucun episode n'est verrouille, donc aucune icone
            // de cadenas ni de prix.
            items(series.episodes, key = { it.number }) { episode ->
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.episode_label, episode.number))
                    },
                    supportingContent = { Text(episode.title) },
                    trailingContent = {
                        Text(
                            stringResource(
                                R.string.episode_duration,
                                episode.durationSeconds / 60,
                                episode.durationSeconds % 60,
                            ),
                        )
                    },
                    modifier = Modifier.clickable { onPlay(episode) },
                )
            }
        }
    }
}

@Composable
private fun PlayerScreen(series: Series?, episodeNumber: Int, onBack: () -> Unit) {
    val episode = series?.episodes?.firstOrNull { it.number == episodeNumber }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Text(
            stringResource(R.string.player_pending),
            color = Color.White,
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(
                series?.title.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(stringResource(R.string.episode_label, episodeNumber), color = Color.White)
            if (episode?.subtitlesUrl != null) {
                Text(stringResource(R.string.player_subtitles_available), color = Color.White)
            }
        }
        IconButton(onBack, Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.action_back),
                tint = Color.White,
            )
        }
    }
}
