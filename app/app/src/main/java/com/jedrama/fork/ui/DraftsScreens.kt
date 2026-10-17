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
import com.jedrama.fork.library.SeriesRepository

/** Liste des series generees, les plus recentes d'abord. */
@Composable
fun DraftsScreen(
    seriesRepository: SeriesRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drafts by seriesRepository.series.collectAsState(emptyList())

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
            )
        }
    }
}