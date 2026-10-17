package com.jedrama.fork.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jedrama.fork.R
import com.jedrama.fork.library.Library
import com.jedrama.fork.library.PlaybackRepository
import com.jedrama.fork.library.SeriesRepository

/**
 * Onglet « Ma liste » : deux onglets internes, favoris et historique de
 * lecture, tous deux alimentés par la bibliothèque locale.
 */
@Composable
fun MyListScreen(
    seriesRepository: SeriesRepository,
    playbackRepository: PlaybackRepository,
    onOpenSeries: (String) -> Unit,
    onPlaySeries: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val library = remember { Library(context) }
    val drafts by seriesRepository.series.collectAsState(emptyList())
    val favorites by playbackRepository.favoriteSeries.collectAsState(emptyList())
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.my_list_tab_favorites)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.my_list_tab_history)) },
            )
        }

        if (tab == 0) {
            val favoriteDrafts = drafts.filter { it.id in favorites }
            if (favoriteDrafts.isEmpty()) {
                EmptyState(stringResource(R.string.favorites_empty))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(favoriteDrafts, key = { it.id }) { draft ->
                        SeriesCard(
                            draft = draft,
                            library = library,
                            isFavorite = true,
                            onClick = { onOpenSeries(draft.id) },
                        )
                    }
                }
            }
        } else {
            // Historique : série avec au moins un épisode vu ou commencé.
            val history = drafts.mapNotNull { draft ->
                val last = library.lastEpisode(draft.id)
                val started = last > 1 || library.position(draft.id, last) > 0L ||
                    library.isWatched(draft.id, 1)
                if (started) draft to last else null
            }
            if (history.isEmpty()) {
                EmptyState(stringResource(R.string.history_empty))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(history, key = { it.first.id }) { (draft, last) ->
                        Column {
                            SeriesCard(
                                draft = draft,
                                library = library,
                                isFavorite = draft.id in favorites,
                                onClick = { onOpenSeries(draft.id) },
                            )
                            Text(
                                stringResource(R.string.history_in_progress, last),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
