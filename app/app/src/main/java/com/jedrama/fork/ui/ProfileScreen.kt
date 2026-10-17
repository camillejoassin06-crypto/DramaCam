package com.jedrama.fork.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Onglet « Profil » : statistiques de la bibliothèque locale et accès aux
 * paramètres. Pas de compte, pas de connexion : l'app est monophrone.
 */
@Composable
fun ProfileScreen(
    seriesRepository: SeriesRepository,
    playbackRepository: PlaybackRepository,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val library = remember { Library(context) }
    val drafts by seriesRepository.series.collectAsState(emptyList())
    val favorites by playbackRepository.favoriteSeries.collectAsState(emptyList())

    val totalEpisodes = remember(drafts) { drafts.sumOf { library.playable(it).size } }
    val watchedEpisodes = remember(drafts) {
        drafts.sumOf { draft -> library.playable(draft).count { library.isWatched(draft.id, it) } }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.profile_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = stringResource(R.string.profile_stat_series),
                value = drafts.size.toString(),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = stringResource(R.string.profile_stat_watched),
                value = "$watchedEpisodes/$totalEpisodes",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = stringResource(R.string.profile_stat_favorites),
                value = favorites.size.toString(),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = stringResource(R.string.profile_stat_storage),
                value = Formatter.formatShortFileSize(context, library.bytesUsed()),
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        Card(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.nav_settings)) },
                supportingContent = { Text(stringResource(R.string.profile_settings_hint)) },
                leadingContent = { Icon(Icons.Filled.Settings, null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                },
            )
        }

        Text(
            stringResource(R.string.settings_personal_mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
