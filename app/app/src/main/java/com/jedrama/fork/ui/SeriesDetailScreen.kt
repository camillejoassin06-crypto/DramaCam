package com.jedrama.fork.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jedrama.fork.R
import com.jedrama.fork.library.Library
import com.jedrama.fork.library.PlaybackRepository
import com.jedrama.fork.library.SeriesRepository

/**
 * Fiche d'une série : affiche, présentation, favori, grille d'épisodes avec
 * état « vu », lecture/reprise et suppression.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    seriesRepository: SeriesRepository,
    playbackRepository: PlaybackRepository,
    onPlay: (Int) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val library = remember { Library(context) }
    val draft = remember(seriesId) { seriesRepository.loadSeries(seriesId) }
    val favorites by playbackRepository.favoriteSeries.collectAsState(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    if (draft == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.drafts_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val playable = remember(draft) { library.playable(draft) }
    val resumeEpisode = remember(playable) {
        val last = library.lastEpisode(draft.id)
        if (last in playable) last else playable.firstOrNull()
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Bandeau : affiche + voile dégradé + titre par-dessus.
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            val cover = remember(seriesId) { library.cover(seriesId) }
            if (cover.exists()) {
                AsyncImage(
                    model = cover,
                    contentDescription = draft.concept.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        ),
                    ),
            )
            IconButton(
                onClick = { playbackRepository.toggleFavorite(seriesId) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                val isFavorite = seriesId in favorites
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.action_unfavorite else R.string.action_favorite,
                    ),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
        }

        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                draft.concept.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                draft.concept.genres.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(draft.concept.logline, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.detail_total_episodes, playable.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (resumeEpisode != null) {
                Button(
                    onClick = { onPlay(resumeEpisode) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text(
                        stringResource(
                            if (resumeEpisode > 1) R.string.detail_resume else R.string.detail_play_first,
                            resumeEpisode,
                        ),
                    )
                }
            }
        }


        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.detail_episodes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                playable.forEach { number ->
                    val watched = library.isWatched(draft.id, number)
                    EpisodeChip(
                        number = number,
                        watched = watched,
                        onClick = { onPlay(number) },
                    )
                }
            }

            // Scripts déjà écrits non encore rendus en vidéo.
            val pending = draft.episodes.filter { it.number !in playable }
            if (pending.isNotEmpty()) {
                Text(
                    stringResource(R.string.detail_pending_scripts, pending.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.Delete, null)
                Text(stringResource(R.string.action_delete))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.draft_delete)) },
            text = { Text(stringResource(R.string.detail_delete_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    seriesRepository.deleteSeries(seriesId)
                    onDeleted()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Pastille d'épisode dans la grille : numéro, teinte si vu, coche. */
@Composable
private fun EpisodeChip(number: Int, watched: Boolean, onClick: () -> Unit) {
    val background = if (watched) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.episode_short, number),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (watched) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.detail_watched),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.height(14.dp),
            )
        }
    }
}

