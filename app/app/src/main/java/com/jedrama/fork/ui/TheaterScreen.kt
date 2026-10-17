package com.jedrama.fork.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jedrama.fork.R
import com.jedrama.fork.library.Library
import com.jedrama.fork.library.PlaybackRepository
import com.jedrama.fork.library.SeriesRepository

/**
 * Onglet « Théâtre » : recherche, reprise de lecture, filtres par genre et
 * grille des séries locales — l'équivalent du catalogue, sans serveur.
 */
@Composable
fun TheaterScreen(
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
    var query by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf<String?>(null) }

    val genres = remember(drafts) { drafts.flatMap { it.concept.genres }.distinct().sorted() }
    val filtered = remember(drafts, query, genre) {
        drafts.filter { draft ->
            val matchesQuery = query.isBlank() ||
                draft.concept.title.contains(query, true) ||
                draft.concept.logline.contains(query, true) ||
                draft.concept.genres.any { it.contains(query, true) }
            val matchesGenre = genre == null || genre in draft.concept.genres
            matchesQuery && matchesGenre
        }
    }
    // Série commencée mais pas terminée : reprise possible.
    val resumable = remember(drafts) {
        drafts.mapNotNull { draft ->
            val playable = library.playable(draft)
            val last = library.lastEpisode(draft.id)
            if (playable.size > 1 && last in playable) draft to last else null
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
            )
        }

        if (query.isBlank() && genre == null && resumable.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.continue_watching),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(resumable, key = { it.first.id }) { (draft, last) ->
                            ContinueCard(
                                draft = draft,
                                episode = last,
                                onClick = { onPlaySeries(draft.id, last) },
                            )
                        }
                    }
                }
            }
        }

        if (genres.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(genres) { g ->
                        FilterChip(
                            selected = genre == g,
                            onClick = { genre = if (genre == g) null else g },
                            label = { Text(g) },
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.search_no_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { draft ->
                SeriesCard(
                    draft = draft,
                    library = library,
                    isFavorite = draft.id in favorites,
                    onClick = { onOpenSeries(draft.id) },
                )
            }
        }
    }
}

