package com.jedrama.fork.library

import com.jedrama.fork.generation.SeriesDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ã‰tat de lecture : reprise, favoris, progression.
 */
interface PlaybackRepository {

    /** Ã‰pisode en cours de lecture pour une sÃ©rie donnÃ©e. */
    fun lastEpisode(seriesId: String): Int

    /** Position de lecture (ms) pour un Ã©pisode. */
    fun position(seriesId: String, episode: Int): Long

    /** Sauvegarde la position de lecture. */
    fun savePosition(seriesId: String, episode: Int, positionMs: Long)

    /** Marque un Ã©pisode comme vu. */
    fun markWatched(seriesId: String, episode: Int)

    /** VÃ©rifie si un Ã©pisode est vu. */
    fun isWatched(seriesId: String, episode: Int): Boolean

    /** Liste des Ã©pisodes jouables d'une sÃ©rie. */
    fun playable(draft: SeriesDraft): List<Int>

    /** Ã‰pisodes vus (pour l'affichage "continue watching"). */
    fun watchedEpisodes(seriesId: String): List<Int>

    /** Ajoute/retire une sÃ©rie des favoris. */
    fun toggleFavorite(seriesId: String)

    /** VÃ©rifie si une sÃ©rie est favorie. */
    fun isFavorite(seriesId: String): Boolean

    /** Liste des sÃ©ries favoris. */
    val favoriteSeries: Flow<List<String>>
}

/** ImplÃ©mentation concrÃ¨te basÃ©e sur SharedPreferences. */
class PlaybackRepositoryImpl(private val library: Library) : PlaybackRepository {

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    override val favoriteSeries: Flow<List<String>> = _favorites.asStateFlow()

    private val prefsKey = "jedrama-favorites"

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
        // Les Ã©pisodes vus sont suivis dans SharedPreferences par Library.
        // Retourne la liste des Ã©pisodes marquÃ©s comme vus.
        return library.playable(draftFromId(seriesId)).filter { isWatched(seriesId, it) }
    }

    override fun toggleFavorite(seriesId: String) {
        val current = _favorites.value
        val updated = if (current.contains(seriesId)) {
            current.filter { it != seriesId }
        } else {
            current + seriesId
        }
        _favorites.value = updated
        saveFavorites(updated)
    }

    override fun isFavorite(seriesId: String): Boolean =
        _favorites.value.contains(seriesId)

    private fun draftFromId(seriesId: String): com.jedrama.fork.generation.SeriesDraft {
        // BibliothÃ¨que simple : on crÃ©e un draft minimal pour l'API playable.
        // En pratique, utiliser SeriesRepository pour avoir le vrai draft.
        return com.jedrama.fork.generation.SeriesDraft(
            concept = com.jedrama.fork.generation.Concept().apply { id = seriesId },
            bible = com.jedrama.fork.generation.Bible(),
        )
    }

    private fun saveFavorites(favorites: List<String>) {
        // Persister dans SharedPreferences (simplifiÃ© : en mÃ©moire pour cette version)
        // Une implÃ©mentation complÃ¨te utiliserait EncryptedSharedPreferences ou DataStore.
    }

    private val loadFavorites: List<String> by lazy {
        emptyList() // Ã€ implÃ©menter avec DataStore ou SharedPreferences
    }
}
