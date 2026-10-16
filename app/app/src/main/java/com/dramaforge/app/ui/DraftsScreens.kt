package com.dramaforge.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dramaforge.app.R
import com.dramaforge.app.generation.EpisodeScript
import com.dramaforge.app.generation.SeriesDraft
import com.dramaforge.app.generation.SeriesDraftStore
import java.text.DateFormat
import java.util.Date

/** Liste des series generees, les plus recentes d'abord. */
@Composable
fun DraftsScreen(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SeriesDraftStore(context) }
    // Relu a chaque entree sur l'ecran : une generation en cours ajoute des
    // episodes pendant ce temps.
    val drafts = remember { store.all() }

    if (drafts.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.drafts_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.drafts_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.drafts_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        items(drafts, key = { it.id }) { draft ->
            DraftCard(draft) { onOpen(draft.id) }
        }
    }
}

@Composable
private fun DraftCard(draft: SeriesDraft, onOpen: () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                color = MaterialTheme.colorScheme.secondary,
            )
            if (!draft.isComplete) {
                Text(
                    stringResource(R.string.drafts_in_progress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (draft.issues.isNotEmpty()) {
                Text(
                    stringResource(R.string.drafts_issues, draft.issues.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(draft.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Detail d'une serie : personnages, points a revoir, puis les episodes. */
@Composable
fun DraftDetailScreen(
    seriesId: String,
    onOpenEpisode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { SeriesDraftStore(context) }
    val draft = remember(seriesId) { store.load(seriesId) } ?: return

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(draft.concept.title, style = MaterialTheme.typography.headlineSmall)
                Text(draft.concept.logline, style = MaterialTheme.typography.bodyMedium)
                Text(
                    draft.concept.genres.joinToString(" · "),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(draft.concept.tone, style = MaterialTheme.typography.bodySmall)
            }
        }

        item { HorizontalDivider() }
        item {
            Text(
                stringResource(R.string.draft_characters),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        items(draft.concept.characters, key = { it.name }) { character ->
            Column {
                Text("${character.name} — ${character.role}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    character.appearance,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (draft.issues.isNotEmpty()) {
            item { HorizontalDivider() }
            item {
                Text(
                    stringResource(R.string.drafts_issues, draft.issues.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            items(draft.issues) { issue ->
                Text(issue, style = MaterialTheme.typography.bodySmall)
            }
        }

        item { HorizontalDivider() }
        item {
            Text(
                stringResource(R.string.detail_episodes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        items(draft.episodes, key = { it.number }) { episode ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenEpisode(episode.number) }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.episode_label, episode.number),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(episode.title, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Script d'un episode : les plans dans l'ordre, avec locuteur et replique. */
@Composable
fun DraftEpisodeScreen(
    seriesId: String,
    episodeNumber: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { SeriesDraftStore(context) }
    val draft = remember(seriesId) { store.load(seriesId) } ?: return
    val episode: EpisodeScript =
        remember(seriesId, episodeNumber) {
            draft.episodes.firstOrNull { it.number == episodeNumber }
        } ?: return

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.episode_label, episode.number),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(episode.title, style = MaterialTheme.typography.titleLarge)
                val total = episode.shots.sumOf { it.durationSeconds }.toInt()
                Text(
                    stringResource(R.string.episode_duration, total / 60, total % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Text(
                stringResource(R.string.draft_shots),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        items(episode.shots, key = { it.index }) { shot ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${shot.index}. ${shot.speaker} · ${shot.durationSeconds.toInt()} s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(shot.line, style = MaterialTheme.typography.bodyMedium)
                Text(
                    shot.imagePrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { HorizontalDivider() }
        item {
            Column {
                Text(
                    stringResource(R.string.draft_cliffhanger),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(episode.cliffhanger, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
