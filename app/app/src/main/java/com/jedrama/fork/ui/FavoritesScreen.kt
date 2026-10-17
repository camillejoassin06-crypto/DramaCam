package com.jedrama.fork.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jedrama.fork.R
import com.jedrama.fork.generation.SeriesDraft
import com.jedrama.fork.library.PlaybackRepository
import com.jedrama.fork.library.SeriesRepository

@Composable
fun FavoritesScreen(
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val seriesRepository: SeriesRepository = com.jedrama.fork.di.RepositoryModule.provideSeriesRepository(
        com.jedrama.fork.generation.SeriesDraftStore(context),
        com.jedrama.fork.library.Library(context)
    )
    val playbackRepository: PlaybackRepository = com.jedrama.fork.di.RepositoryModule.providePlaybackRepository(
        com.jedrama.fork.library.Library(context)
    )

    val favoriteIds by playbackRepository.favoriteSeries.collectAsState(emptyList())

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.favorites_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        if (favoriteIds.isEmpty()) {
            Text(
                stringResource(R.string.favorites_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(favoriteIds, key = { it }) { seriesId ->
                val draft = seriesRepository.loadSeries(seriesId)
                if (draft == null) return@items

                Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = { onOpen(seriesId) }),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            draft.concept.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            draft.concept.logline,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                        Text(
                            stringResource(
                                R.string.generation_status_progress,
                                draft.episodes.size,
                                draft.targetEpisodeCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}