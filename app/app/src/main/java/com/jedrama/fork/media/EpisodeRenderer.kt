package com.jedrama.fork.media

import com.jedrama.fork.generation.Concept
import com.jedrama.fork.generation.EpisodeScript
import com.jedrama.fork.generation.GenerationLimits
import com.jedrama.fork.generation.Shot
import java.io.File
import java.util.Locale

/** Reglages de rendu, issus des Parametres. */
data class RenderSettings(
    val width: Int = 720,
    val height: Int = 1280,
    val fps: Int = 25,
    /** Nappe musicale generee par FFmpeg, compressee sous la voix. */
    val ambience: Boolean = true,
)

/**
 * Lignes de commande FFmpeg du montage. Fonctions pures : elles sont testees
 * telles quelles sur un FFmpeg de bureau compile avec la meme configuration
 * (cf. FfmpegCommandsTest), ce qui verifie que chaque filtre est bien embarque.
 */
object FfmpegCommands {
    /** Sur-echantillonnage avant zoompan : limite l'escalier du mouvement. */
    private const val OVERSCAN = 1.15
    /**
     * zoompan ne se deplace que par pixels entiers de l'entree : plus elle est
     * grande, plus le mouvement est fluide. Mesure : saccade 0,48 px a 1,5,
     * 0,31 px a 2,5, pour +40 % de temps de montage.
     */
    private const val BASE_SCALE = 2.5

    /** Un plan = image fixe en mouvement lent (zoom alterne) + sa voix. */
    fun clip(
        image: File,
        voice: File,
        out: File,
        durationSeconds: Double,
        index: Int,
        settings: RenderSettings,
    ): List<String> {
        val frames = Math.max(1, Math.round(durationSeconds * settings.fps).toInt())
        val step = fmt((OVERSCAN - 1.0) / frames)
        val zoom = if (index % 2 == 0) "1+$step*on" else "$OVERSCAN-$step*on"
        val baseW = even(settings.width * BASE_SCALE)
        val baseH = even(settings.height * BASE_SCALE)
        val video =
            // out_range=tv : les JPEG sont en pleine gamme ; sans conversion la
            // video sortait marquee yuvj420p (couleurs fausses selon le lecteur).
            "[0:v]scale=$baseW:$baseH:force_original_aspect_ratio=increase:out_range=tv,crop=$baseW:$baseH,setsar=1," +
                "zoompan=z='$zoom':d=$frames:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'" +
                ":s=${settings.width}x${settings.height}:fps=${settings.fps}," +
                "fade=t=in:st=0:d=0.35,format=yuv420p[v]"
        val audio = "[1:a]aresample=48000,aformat=channel_layouts=stereo,apad[a]"
        return listOf(
            "-i", image.path,
            "-i", voice.path,
            "-filter_complex", "$video;$audio",
            "-map", "[v]", "-map", "[a]",
            "-t", fmt(durationSeconds),
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "27", "-pix_fmt", "yuv420p",
            "-g", (settings.fps * 4).toString(),
            "-c:a", "aac", "-b:a", "96k", "-ar", "48000", "-ac", "2",
            out.path,
        )
    }

    /** Liste du demuxeur concat : chemins entre apostrophes, echappees. */
    fun concatList(clips: List<File>): String =
        clips.joinToString("\n") { "file '${it.path.replace("'", "'\\''")}'" }

    /**
     * Assemblage des plans ; seule la piste audio est re-encodee.
     *
     * Chaque plan AAC deborde d'environ 13 ms que le demuxeur concat ignore : sans
     * `aresample=async`, la voix derivait de ~0,1 s sur 8 plans. Avec la nappe, la
     * musique est compressee par la voix (ducking) puis melangee dessous.
     */
    fun concat(
        list: File,
        out: File,
        totalSeconds: Double,
        settings: RenderSettings,
        seed: Int,
    ): List<String> {
        val head = listOf("-f", "concat", "-safe", "0", "-i", list.path)
        val tail = listOf("-movflags", "+faststart", out.path)
        val resync = "aresample=async=1:min_hard_comp=0.01:first_pts=0"
        val audioCodec = listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "96k")
        if (!settings.ambience) {
            return head + listOf("-filter_complex", "[0:a]$resync[aout]", "-map", "0:v", "-map", "[aout]") +
                audioCodec + tail
        }

        val fadeOut = fmt((totalSeconds - 3).coerceAtLeast(0.0))
        return head + listOf(
            "-f", "lavfi", "-i", "aevalsrc='${drone(seed)}':s=48000:d=${fmt(totalSeconds)}",
            "-filter_complex",
            "[0:a]$resync,asplit=2[v1][v2];" +
                "[1:a]lowpass=f=1200,afade=t=in:d=3,afade=t=out:st=$fadeOut:d=3," +
                "aformat=channel_layouts=stereo,volume=1.0[m];" +
                "[m][v1]sidechaincompress=threshold=0.04:ratio=4:attack=20:release=500[duck];" +
                "[v2][duck]amix=inputs=2:duration=first:dropout_transition=0:normalize=0," +
                "alimiter=limit=0.95[aout]",
            "-map", "0:v", "-map", "[aout]",
        ) + audioCodec + tail
    }

    /**
     * Nappe en accord mineur (fondamentale, tierce, quinte, octave basse) avec
     * des respirations lentes ; la tonalite change avec la serie. Fondamentales
     * autour de 200-260 Hz : plus bas, un haut-parleur de telephone ne rend rien.
     */
    fun drone(seed: Int): String {
        val roots = listOf(196.0, 207.65, 220.0, 233.08, 246.94, 261.62)
        val f = roots[Math.floorMod(seed, roots.size)]
        fun tone(freq: Double, amp: Double, lfo: Double, phase: Int) =
            "${fmt(amp)}*sin(2*PI*${fmt(freq)}*t)*(0.7+0.3*sin(2*PI*${fmt(lfo)}*t+$phase))"
        return listOf(
            tone(f, 0.05, 0.07, 0),
            tone(f * 1.2, 0.035, 0.05, 1),
            tone(f * 1.5, 0.03, 0.09, 2),
            tone(f * 0.5, 0.04, 0.03, 3),
        ).joinToString("+")
    }

    private fun even(value: Double): Int = (Math.round(value / 2) * 2).toInt()

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
}

/** Choix purs du montage, testables sans Android. */
object RenderPlan {
    /**
     * Duree d'un plan : la voix d'abord, puis un temps de respiration borne par
     * la duree prevue au script. Jamais une minute de silence sur une image.
     */
    fun shotSeconds(voiceSeconds: Double, scriptedSeconds: Double): Double =
        maxOf(voiceSeconds + 0.6, minOf(scriptedSeconds, voiceSeconds + 2.5))

    /** Prompt d'image : cadre du script + physique figee des personnages presents + style. */
    fun imagePrompt(concept: Concept, shot: Shot): String {
        val present = concept.characters.filter { shot.imagePrompt.contains(it.name, ignoreCase = true) }
        val bodies = present.joinToString(". ") { "${it.name}: ${it.appearance}" }
        return listOf(shot.imagePrompt, bodies, Images.STYLE_LOCK).filter { it.isNotBlank() }.joinToString(". ")
    }

    fun coverPrompt(concept: Concept): String {
        val lead = concept.characters.firstOrNull()
        return listOf(
            "Poster key art for a vertical drama: ${concept.logline}",
            lead?.let { "${it.name}: ${it.appearance}" }.orEmpty(),
            Images.STYLE_LOCK,
        ).filter { it.isNotBlank() }.joinToString(". ")
    }

    fun voiceOf(concept: Concept, speaker: String): String =
        if (speaker == GenerationLimits.NARRATOR) Speech.NARRATOR_VOICE
        else concept.characters.firstOrNull { it.name == speaker }?.voiceId ?: Speech.NARRATOR_VOICE

    fun seedOf(seriesId: String, episode: Int, shot: Int): Int =
        Math.floorMod(seriesId.hashCode() * 31 + episode * 101 + shot, 1_000_000)
}

/**
 * Rendu complet d'un episode sur le telephone : voix (TTS), images, montage
 * FFmpeg, sous-titres. Sorties : ep-NNN.mp4 et ep-NNN.vtt dans [outDir].
 */
class EpisodeRenderer(
    private val ffmpeg: Ffmpeg,
    private val speech: Speech,
    private val images: Images,
    private val settings: RenderSettings,
) {
    data class Result(val video: File, val subtitles: File, val durationSeconds: Int, val aiImages: Int)

    suspend fun render(
        concept: Concept,
        script: EpisodeScript,
        outDir: File,
        workDir: File,
        onShot: suspend (done: Int, total: Int) -> Unit,
    ): Result {
        // Le dossier de travail n'est PAS vide au demarrage : sans avant-plan
        // (Android 12+ le refuse souvent en arriere-plan), le job est coupe au
        // bout de ~10 minutes. Les plans deja montes sont alors repris tels
        // quels, et l'episode avance d'une execution a l'autre.
        // Cle = reglages de rendu : un plan 720p ne doit jamais etre concatene
        // en copie avec un plan 1080p (changement de reglage en cours d'episode).
        val episodeDir = File(workDir, "ep-${script.number}-${settings.width}x${settings.height}@${settings.fps}")
        episodeDir.mkdirs()
        outDir.mkdirs()

        val clips = mutableListOf<File>()
        val segments = mutableListOf<Subtitles.Segment>()
        var cursor = 0.0
        var aiImages = 0

        script.shots.forEachIndexed { index, shot ->
            val number = index + 1
            val clip = File(episodeDir, "clip-%02d.mp4".format(Locale.ROOT, number))
            val meta = File(episodeDir, "clip-%02d.txt".format(Locale.ROOT, number))
            val done = meta.takeIf { it.exists() && clip.exists() }?.readText()?.split(' ')
                ?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }

            val (voiceSeconds, seconds, ai) = done ?: run {
                val voice = File(episodeDir, "shot-$number.wav")
                speech.synthesize(shot.line, RenderPlan.voiceOf(concept, shot.speaker), voice)
                val voiceSeconds = Wav.durationSeconds(voice)
                val seconds = RenderPlan.shotSeconds(voiceSeconds, shot.durationSeconds)

                val image = File(episodeDir, "shot-$number.jpg")
                val ai = images.shot(RenderPlan.imagePrompt(concept, shot), RenderPlan.seedOf(concept.id, script.number, number), image)

                ffmpeg.run(FfmpegCommands.clip(image, voice, clip, seconds, index, settings))
                meta.writeText("$voiceSeconds $seconds ${if (ai) 1.0 else 0.0}")
                image.delete()
                voice.delete()
                listOf(voiceSeconds, seconds, if (ai) 1.0 else 0.0)
            }
            clips += clip
            if (ai > 0) aiImages++
            segments += Subtitles.Segment(shot.line, cursor, voiceSeconds)
            cursor += seconds
            onShot(number, script.shots.size)
        }

        val name = "ep-%03d".format(Locale.ROOT, script.number)
        val list = File(episodeDir, "clips.txt").apply { writeText(FfmpegCommands.concatList(clips)) }
        val partial = File(episodeDir, "$name.mp4")
        ffmpeg.run(FfmpegCommands.concat(list, partial, cursor, settings, concept.id.hashCode()))

        val video = File(outDir, "$name.mp4")
        val subtitles = File(outDir, "$name.vtt").apply { writeText(Subtitles.toVtt(segments)) }
        if (!partial.renameTo(video)) {
            partial.copyTo(video, overwrite = true)
        }
        episodeDir.deleteRecursively()
        return Result(video, subtitles, Math.round(cursor).toInt(), aiImages)
    }
}

