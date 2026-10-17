package com.jedrama.fork.library

import android.content.Context
import android.content.SharedPreferences
import com.jedrama.fork.generation.SeriesDraft
import com.jedrama.fork.generation.SeriesDraftStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** État de lecture : reprise, favoris, progression. */
interface PlaybackRepository {

    /** Épisode en cours de lecture pour une série donnée. */
    fun lastEpisode(seriesId: String): Int

    /** Position de lecture (ms) pour un épisode. */
    fun position(seriesId: String, episode: Int): Long

    /** Sauvegarde la position de lecture. */
    fun savePosition(seriesId: String, episode: Int, positionMs: Long)

    /** Marque un épisode comme vu. */
    fun markWatched(seriesId: String, episode: Int)

    /** Vérifie si un épisode est vu. */
    fun isWatched(seriesId: String, episode: Int): Boolean

    /** Liste des épisodes jouables d'une série. */
    fun playable(draft: SeriesDraft): List<Int>

    /** Épisodes vus (pour l'affichage « continue watching »). */
    fun watchedEpisodes(seriesId: String): List<Int>

    /** Ajoute/retire une série des favoris (« Ma liste »). */
    fun toggleFavorite(seriesId: String)

    /** Vérifie si une série est favorie. */
    fun isFavorite(seriesId: String): Boolean

    /** Liste des séries favoris. */
    val favoriteSeries: Flow<List<String>>
}

/**
 * Implémentation concrète basée sur SharedPreferences.
 *
 * Les favoris (« Ma liste ») persistent entre les lancements : sans cela, la
 * liste se réinitialisait à chaque démarrage de l'application.
 */
class PlaybackRepositoryImpl(
    private val library: Library,
    private val store: SeriesDraftStore,
    private val prefs: SharedPreferences,
) : PlaybackRepository {

    private val _favorites = MutableStateFlow(
        prefs.getStringSet(KEY_FAVORITES, emptySet())?.toList().orEmpty(),
    )
    override val favoriteSeries: Flow<List<String>> = _favorites.asStateFlow()

    override fun lastEpisode(seriesId: String): Int = library.lastEpisode(seriesId)

    override fun position(seriesId: String, episode: Int): Long =
        library.position(seriesId, episode)

    override fun savePosition(seriesId: String, episode: Int, positionMs: Long) {
        library.save(seriesId, episode, positionMs)
    }

    override fun markWatched(seriesId: String, episode: Int) {
        library.markWatched(seriesId, episode)
    }

    override fun isWatched(seriesId: String, episode: Int): Boolean =
        library.isWatched(seriesId, episode)

    override fun playable(draft: SeriesDraft): List<Int> = library.playable(draft)

    override fun watchedEpisodes(seriesId: String): List<Int> {
        val draft = store.load(seriesId) ?: return emptyList()
        return library.playable(draft).filter { isWatched(seriesId, it) }
    }

    override fun toggleFavorite(seriesId: String) {
        val current = _favorites.value
        val updated = if (current.contains(seriesId)) {
            current - seriesId
        } else {
            current + seriesId
        }
        _favorites.value = updated
        prefs.edit().putStringSet(KEY_FAVORITES, updated.toSet()).apply()
    }

    override fun isFavorite(seriesId: String): Boolean =
        _favorites.value.contains(seriesId)

    private companion object {
        const val KEY_FAVORITES = "favorites"
    }
}

/** Préférences de suivi de lecture (fournies par le module Hilt). */
fun playbackPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences("jedrama-playback", Context.MODE_PRIVATE)