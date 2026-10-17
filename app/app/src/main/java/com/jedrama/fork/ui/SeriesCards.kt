package com.jedrama.fork.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jedrama.fork.R
import com.jedrama.fork.generation.SeriesDraft
import com.jedrama.fork.library.Library
import java.io.File

/** Fichier d'affiche local d'une série (peut ne pas exister). */
@Composable
private fun rememberCover(seriesId: String): File {
    val context = LocalContext.current
    return remember(seriesId) { Library(context).cover(seriesId) }
}

/**
 * Carte de série : affiche locale, titre, genres, progression.
 * Utilisée par le Théâtre et par « Ma liste ».
 */
@Composable
fun SeriesCard(
    draft: SeriesDraft,
    library: Library,
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cover = rememberCover(draft.id)
    val playable = library.playable(draft)
    val watched = playable.count { library.isWatched(draft.id, it) }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                if (cover.exists()) {
                    AsyncImage(
                        model = cover,
                        contentDescription = draft.concept.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Pas d'affiche : aplat sombre avec l'initiale du titre.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            draft.concept.title.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (isFavorite) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = stringResource(R.string.action_favorite),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
                if (playable.isNotEmpty()) {
                    Text(
                        stringResource(R.string.common_episodes_count, playable.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    draft.concept.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    draft.concept.genres.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playable.isNotEmpty()) {
                    LinearProgressIndicator(
                        progress = { watched.toFloat() / playable.size },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Carte compacte de la rangée « Reprendre la lecture » : affiche, titre et
 * numéro d'épisode, un toucher reprend la lecture.
 */
@Composable
fun ContinueCard(
    draft: SeriesDraft,
    episode: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cover = rememberCover(draft.id)
    Card(
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.width(120.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
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
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            draft.concept.title.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Column(Modifier.padding(6.dp)) {
                Text(
                    draft.concept.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.episode_label, episode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

