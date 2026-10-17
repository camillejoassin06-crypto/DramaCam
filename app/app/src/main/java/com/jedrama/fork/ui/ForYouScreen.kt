package com.jedrama.fork.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.jedrama.fork.R
import com.jedrama.fork.generation.SeriesDraftStore
import com.jedrama.fork.library.Library
import com.jedrama.fork.library.PlaybackRepository
import com.jedrama.fork.library.SeriesRepository
import kotlinx.coroutines.delay

/** Un épisode du fil : identifiant de série + numéro, dans l'ordre de lecture. */
private data class FeedItem(val seriesId: String, val episode: Int)

/**
 * Onglet « Pour vous » : fil vertical plein écran qui enchaîne les épisodes
 * de toutes les séries, les plus récentes d'abord. Un seul ExoPlayer, seule
 * la page visible joue, reprise à la dernière position, favori en un toucher.
 */
@OptIn(UnstableApi::class)
@Composable
fun ForYouScreen(
    seriesRepository: SeriesRepository,
    playbackRepository: PlaybackRepository,
    onOpenSeries: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val library = remember { Library(context) }
    val store = remember { SeriesDraftStore(context) }
    val drafts by seriesRepository.series.collectAsState(emptyList())
    val favorites by playbackRepository.favoriteSeries.collectAsState(emptyList())

    val feed = remember(drafts) {
        drafts.flatMap { draft -> library.playable(draft).map { FeedItem(draft.id, it) } }
    }

    if (feed.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.feed_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    // On démarre au premier épisode non vu, sinon en tête de fil.
    val initialPage = feed.indexOfFirst { !library.isWatched(it.seriesId, it.episode) }
        .coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initialPage) { feed.size }
    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    var ended by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf<FeedItem?>(null) }

    // Écran éteint ou app en arrière-plan : pause + sauvegarde.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                loaded?.let { library.save(it.seriesId, it.episode, player.currentPosition) }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended++
            }
        }
        player.addListener(listener)
        onDispose {
            val item = loaded
            if (item != null && player.playbackState != Player.STATE_ENDED) {
                library.save(item.seriesId, item.episode, player.currentPosition)
            }
            player.removeListener(listener)
            player.release()
        }
    }

    // Page stabilisée : sauvegarde l'épisode quitté et charge le nouveau.
    LaunchedEffect(pager.settledPage) {
        val item = feed[pager.settledPage]
        if (item == loaded) return@LaunchedEffect
        loaded?.let { previous ->
            if (player.playbackState != Player.STATE_ENDED) {
                library.save(previous.seriesId, previous.episode, player.currentPosition)
            }
        }
        loaded = item
        val subtitles = library.subtitles(item.seriesId, item.episode)
        val media = MediaItem.Builder()
            .setUri(Uri.fromFile(library.video(item.seriesId, item.episode)))
            .apply {
                if (subtitles.exists()) {
                    setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitles))
                                .setMimeType(MimeTypes.TEXT_VTT)
                                .setLanguage("fr")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build(),
                        ),
                    )
                }
            }
            .build()
        player.setMediaItem(media, library.position(item.seriesId, item.episode))
        player.prepare()
        player.playWhenReady = true
    }

    // Sauvegarde périodique de la position.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            val item = loaded
            if (player.isPlaying && item != null) {
                library.save(item.seriesId, item.episode, player.currentPosition)
            }
        }
    }

    // Fin d'épisode : marque vu puis enchaîne (y compris sur la série suivante).
    LaunchedEffect(ended) {
        if (ended == 0) return@LaunchedEffect
        loaded?.let { library.markWatched(it.seriesId, it.episode) }
        if (pager.settledPage < feed.lastIndex) {
            pager.animateScrollToPage(pager.settledPage + 1)
        }
    }


    Box(modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val item = feed[page]
            val draft = remember(item.seriesId) { store.load(item.seriesId) }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (page == pager.settledPage) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = true
                                controllerShowTimeoutMs = 2_000
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                            }
                        },
                        update = { view -> view.player = player },
                        onRelease = { view -> view.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Voile dégradé bas pour la lisibilité du texte sur la vidéo.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                            ),
                        ),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, end = 88.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        draft?.concept?.title.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onOpenSeries(item.seriesId) },
                    )
                    Text(
                        draft?.concept?.logline.orEmpty(),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    Text(
                        stringResource(
                            R.string.feed_episode_of,
                            item.episode,
                            draft?.let { library.playable(it).size } ?: item.episode,
                        ),
                        color = Gold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val isFavorite = item.seriesId in favorites
                    IconButton(onClick = { playbackRepository.toggleFavorite(item.seriesId) }) {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = stringResource(
                                if (isFavorite) R.string.action_unfavorite else R.string.action_favorite,
                            ),
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    IconButton(onClick = { onOpenSeries(item.seriesId) }) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = stringResource(R.string.feed_open_series),
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }
    }
}

