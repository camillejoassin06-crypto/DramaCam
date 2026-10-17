package com.jedrama.fork.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lecteur vertical : un episode par page, swipe vers le haut pour le suivant,
 * enchainement automatique en fin d'episode, reprise a la derniere position.
 * Un seul ExoPlayer pour toute la serie : seule la page visible joue.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(seriesId: String, startEpisode: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val library = remember { Library(context) }
    val draft = remember(seriesId) { SeriesDraftStore(context).load(seriesId) }
    val episodes = remember(draft) { draft?.let { library.playable(it) }.orEmpty() }
    if (draft == null || episodes.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val pager = rememberPagerState(initialPage = episodes.indexOf(startEpisode).coerceAtLeast(0)) { episodes.size }
    val scope = rememberCoroutineScope()
    // Focus audio (pause sur appel ou autre app) et pause si le casque est debranche.
    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    var ended by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf<Int?>(null) }

    // Ecran eteint ou app en arriere-plan : pause et sauvegarde de la position.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                loaded?.let { library.save(seriesId, it, player.currentPosition) }
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
            val number = loaded
            if (number != null && player.playbackState != Player.STATE_ENDED) {
                library.save(seriesId, number, player.currentPosition)
            }
            player.removeListener(listener)
            player.release()
        }
    }

    // Page stabilisee (et non en plein swipe) : on sauvegarde l'episode quitte
    // et on charge le nouveau.
    LaunchedEffect(pager.settledPage) {
        val number = episodes[pager.settledPage]
        if (number == loaded) return@LaunchedEffect
        loaded?.let { previous ->
            if (player.playbackState != Player.STATE_ENDED) library.save(seriesId, previous, player.currentPosition)
        }
        loaded = number
        val subtitles = library.subtitles(seriesId, number)
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(library.video(seriesId, number)))
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
        player.setMediaItem(item, library.position(seriesId, number))
        player.prepare()
        player.playWhenReady = true
        library.save(seriesId, number, library.position(seriesId, number))
    }

    // Sauvegarde reguliere de la position, pour une reprise precise.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            val number = loaded
            if (player.isPlaying && number != null) library.save(seriesId, number, player.currentPosition)
        }
    }

    // Fin d'episode : marque vu, puis page suivante.
    LaunchedEffect(ended) {
        if (ended == 0) return@LaunchedEffect
        loaded?.let { library.markWatched(seriesId, it) }
        if (pager.settledPage < episodes.lastIndex) scope.launch { pager.animateScrollToPage(pager.settledPage + 1) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
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
                val number = episodes[page]
                Column(Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 20.dp, end = 16.dp)) {
                    Text(draft.concept.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.episode_label, number) + " Â· " + draft.script(number)?.title.orEmpty(),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }
        IconButton(onBack, Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
        }
    }
}

