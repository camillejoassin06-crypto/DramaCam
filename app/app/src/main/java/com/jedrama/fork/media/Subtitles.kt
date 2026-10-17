package com.jedrama.fork.media

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Sous-titres WebVTT calcules sur la duree REELLE de chaque replique (mesuree
 * sur l'audio), pas sur l'estimation du modele : c'est ce qui garantit
 * l'alignement. Portage de l'ancien pipeline/src/subtitles.ts.
 */
object Subtitles {
    const val MAX_CHARS_PER_LINE = 42
    const val MAX_LINES_PER_CUE = 2

    data class Segment(val text: String, val startSeconds: Double, val durationSeconds: Double)
    data class Cue(val startSeconds: Double, val endSeconds: Double, val text: String)

    fun formatTimestamp(totalSeconds: Double): String {
        val millisTotal = Math.round(totalSeconds.coerceAtLeast(0.0) * 1000)
        val hours = millisTotal / 3_600_000
        val minutes = (millisTotal % 3_600_000) / 60_000
        val seconds = (millisTotal % 60_000) / 1000
        val millis = millisTotal % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    /** Coupe sur les espaces, sans jamais tronquer un mot. */
    fun wrapLines(text: String, maxChars: Int = MAX_CHARS_PER_LINE): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            current = when {
                current.isEmpty() -> word
                "$current $word".length <= maxChars -> "$current $word"
                else -> { lines += current; word }
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    /**
     * Une replique trop longue pour deux lignes est scindee en plusieurs cues,
     * dont la duree est proportionnelle au nombre de caracteres.
     */
    fun cuesFor(segment: Segment): List<Cue> {
        val groups = wrapLines(segment.text).chunked(MAX_LINES_PER_CUE).map { it.joinToString("\n") }
        if (groups.isEmpty()) return emptyList()
        val totalChars = groups.sumOf { it.length }.toDouble()
        var cursor = segment.startSeconds
        return groups.map { group ->
            val duration = segment.durationSeconds * group.length / totalChars
            Cue(cursor, cursor + duration, group).also { cursor += duration }
        }
    }

    fun toVtt(segments: List<Segment>): String {
        val cues = segments.flatMap { cuesFor(it) }
        val blocks = cues.mapIndexed { index, cue ->
            "${index + 1}\n${formatTimestamp(cue.startSeconds)} --> ${formatTimestamp(cue.endSeconds)}\n${cue.text}"
        }
        return "WEBVTT\n\n${blocks.joinToString("\n\n")}\n"
    }
}

/** Duree d'un WAV PCM, lue dans son en-tete (chunks fmt et data). */
object Wav {
    fun durationSeconds(file: File): Double = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(12)
        raf.readFully(header)
        require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") { "pas un WAV : ${file.name}" }
        var byteRate = 0
        while (raf.filePointer + 8 <= raf.length()) {
            val id = ByteArray(4).also { raf.readFully(it) }.let { String(it) }
            val size = Integer.reverseBytes(raf.readInt()).toLong() and 0xffffffffL
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size.toInt()).also { raf.readFully(it) }
                    byteRate = (fmt[8].toInt() and 0xff) or ((fmt[9].toInt() and 0xff) shl 8) or
                        ((fmt[10].toInt() and 0xff) shl 16) or ((fmt[11].toInt() and 0xff) shl 24)
                }
                "data" -> {
                    require(byteRate > 0) { "en-tete WAV sans debit" }
                    // Certains moteurs laissent la taille a 0 ou a 0xFFFFFFFF tant
                    // que le fichier n'est pas finalise : on mesure alors le reste.
                    val remaining = raf.length() - raf.filePointer
                    val bytes = if (size == 0L || size > remaining) remaining else size
                    return@use bytes.toDouble() / byteRate
                }
                else -> raf.seek(raf.filePointer + size + (size and 1))
            }
        }
        error("WAV sans donnees : ${file.name}")
    }
}

