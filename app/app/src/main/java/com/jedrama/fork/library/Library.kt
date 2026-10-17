package com.jedrama.fork.library

import android.content.Context
import com.jedrama.fork.generation.SeriesDraft
import java.io.File
import java.util.Locale

/**
 * Bibliotheque locale : les series rendues sur le telephone.
 *
 * filesDir/library/<serie>/cover.jpg, ep-001.mp4, ep-001.vtt...
 * Rien ne sort de l'appareil : pas de serveur, pas de compte.
 */
class Library(context: Context) {
    private val root = File(context.filesDir, "library")
    private val progress = context.getSharedPreferences("jedrama-progress", Context.MODE_PRIVATE)

    fun seriesDir(id: String): File = File(root, id)
    fun cover(id: String): File = File(seriesDir(id), "cover.jpg")
    fun video(id: String, episode: Int): File = File(seriesDir(id), "ep-%03d.mp4".format(Locale.ROOT, episode))
    fun subtitles(id: String, episode: Int): File = File(seriesDir(id), "ep-%03d.vtt".format(Locale.ROOT, episode))

    /** Episodes reellement presents sur le disque, dans l'ordre. */
    fun playable(draft: SeriesDraft): List<Int> =
        draft.rendered.keys.sorted().filter { video(draft.id, it).exists() }

    fun bytesUsed(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // --- Reprise de lecture -------------------------------------------------

    fun lastEpisode(id: String): Int = progress.getInt("$id/episode", 1)

    fun position(id: String, episode: Int): Long = progress.getLong("$id/$episode/position", 0L)

    fun save(id: String, episode: Int, positionMs: Long) {
        progress.edit()
            .putInt("$id/episode", episode)
            .putLong("$id/$episode/position", positionMs)
            .apply()
    }

    fun markWatched(id: String, episode: Int) {
        progress.edit().putBoolean("$id/$episode/watched", true).putLong("$id/$episode/position", 0L).apply()
    }

    fun isWatched(id: String, episode: Int): Boolean = progress.getBoolean("$id/$episode/watched", false)
}

