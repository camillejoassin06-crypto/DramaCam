package com.jedrama.fork.generation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jedrama.fork.R
import com.jedrama.fork.library.Library
import com.jedrama.fork.media.EpisodeRenderer
import com.jedrama.fork.media.Ffmpeg
import com.jedrama.fork.media.ImageProvider
import com.jedrama.fork.media.Images
import com.jedrama.fork.media.RenderPlan
import com.jedrama.fork.media.Speech
import com.jedrama.fork.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Le studio quotidien, entierement sur le telephone :
 *
 *   concept + bible (modele de texte) -> pour chaque episode du jour :
 *   script -> revue -> voix (TTS Android) -> images -> montage FFmpeg -> .mp4 + .vtt
 *
 * Chaque execution rend au plus `episodesPerDay` episodes : la serie en cours
 * avance chaque jour, et une nouvelle commence quand elle est finie.
 *
 * Tout est persiste etape par etape (script, puis video) : une interruption
 * (batterie, processus tue, reseau) reprend a l'episode manquant, sans
 * regenerer ni refacturer ce qui existe deja.
 */
class DailySeriesWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(context)
        if (!settings.hasApiKey()) {
            return fail(settings, context.getString(R.string.generation_status_no_key))
        }
        val ffmpeg = Ffmpeg(context)
        if (!ffmpeg.isAvailable) {
            return fail(settings, context.getString(R.string.generation_status_no_ffmpeg))
        }

        // Une generation manuelle et la quotidienne ne doivent jamais ecrire la
        // meme serie en meme temps : la seconde attend son tour.
        if (!running.tryLock()) return Result.retry()

        var speech: Speech? = null
        return try {
            // Passage en avant-plan des le depart : sur une reprise, le premier
            // message n'arriverait qu'apres un plan complet.
            publish(settings, context.getString(R.string.settings_running), 0, 0)
            withContext(Dispatchers.IO) {
                speech = Speech.create(context)
                produce(settings, ffmpeg, speech!!)
            }
        } catch (error: TimeoutCancellationException) {
            // Sous-classe de CancellationException : sans ce cas, un moteur TTS
            // bloque terminait le job sans message.
            fail(settings, "Delai depasse : ${error.message}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: GenerationException) {
            val message = error.message.orEmpty()
            if (!error.retryable) return fail(settings, message)
            if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
                return fail(settings, context.getString(R.string.generation_status_gave_up, runAttemptCount, message))
            }
            settings.lastStatus = message
            Result.retry()
        } catch (error: Exception) {
            // Filet de securite : une erreur inattendue (FFmpeg, synthese vocale,
            // disque plein) doit laisser un message lisible a l'ecran.
            fail(settings, describeError(error))
        } catch (error: Throwable) {
            // Les erreurs de liaison (par ex. classe manquante après R8) ne sont
            // pas des Exception. Sans ce filet, WorkManager marque le travail
            // FAILED sans outputData et l'UI ne peut afficher que l'ancien état.
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            fail(settings, "Erreur interne : ${describeError(error)}")
        } finally {
            // La fermeture du moteur TTS ne doit jamais remplacer le résultat
            // obtenu (ou empêcher la libération du verrou) si Android a déjà
            // coupé son service vocal.
            try {
                speech?.shutdown()
            } catch (_: Throwable) {
                // Le rendu est déjà terminé ou l'échec a déjà été enregistré.
            } finally {
                running.unlock()
            }
        }
    }

    private suspend fun produce(settings: SettingsStore, ffmpeg: Ffmpeg, speech: Speech): Result {
        val store = SeriesDraftStore(context)
        val library = Library(context)
        val generator = ScriptGenerator(settings.textModel(), Prompts(context))
        val render = settings.renderSettings()
        val images = Images(settings.imageProvider, render.width, render.height)
        val renderer = EpisodeRenderer(ffmpeg, speech, images, render)

        var budget = settings.episodesPerDay
        var lastDraft: SeriesDraft? = null
        var renderedToday = 0

        var newSeriesToday = 0

        while (budget > 0) {
            val draft = store.pending() ?: run {
                // Garde-fou de cout : jamais plus de deux series ouvertes par jour.
                if (newSeriesToday >= MAX_NEW_SERIES_PER_RUN) return@run null
                newSeriesToday++
                newSeries(settings, generator, store)
            } ?: break
            lastDraft = draft

            if (!draft.hasCover) {
                publish(settings, context.getString(R.string.generation_status_cover, draft.concept.title), 0, 0)
                images.cover(RenderPlan.coverPrompt(draft.concept), draft.concept.title,
                    RenderPlan.seedOf(draft.id, 0, 0), library.cover(draft.id))
                draft.hasCover = true
                store.save(draft)
            }

            val number = draft.nextToRender
            if (number == null) {
                draft.completedAt = System.currentTimeMillis()
                store.save(draft)
                continue
            }

            if (isStopped) {
                settings.lastStatus = context.getString(R.string.generation_status_interrupted, number, draft.targetEpisodeCount)
                return Result.retry()
            }

            val script = draft.script(number) ?: writeEpisode(settings, generator, store, draft, number)
            // Serie supprimee depuis l'ecran pendant le travail : on ne la recree pas.
            if (!store.exists(draft.id)) break

            val renderedBefore = renderedToday
            val result = renderer.render(
                concept = draft.concept,
                script = script,
                outDir = library.seriesDir(draft.id),
                workDir = File(context.cacheDir, "render/${draft.id}"),
            ) { done, total ->
                publish(
                    settings,
                    context.getString(R.string.generation_status_rendering, number, done, total),
                    renderedBefore * total + done,
                    settings.episodesPerDay * total,
                )
            }
            if (result.aiImages < script.shots.size && settings.imageProvider == ImageProvider.POLLINATIONS) {
                draft.issues += context.getString(R.string.generation_issue_offline_images, number, script.shots.size - result.aiImages)
            }
            if (!store.exists(draft.id)) {
                library.seriesDir(draft.id).deleteRecursively()
                break
            }
            draft.rendered[number] = result.durationSeconds
            if (draft.nextToRender == null) draft.completedAt = System.currentTimeMillis()
            store.save(draft)
            renderedToday++
            budget--
        }

        val message = lastDraft?.let {
            context.getString(R.string.generation_status_done, renderedToday, it.concept.title, it.rendered.size, it.targetEpisodeCount)
        }.orEmpty()
        settings.lastStatus = message
        return Result.success(workDataOf(KEY_MESSAGE to message))
    }

    private suspend fun newSeries(settings: SettingsStore, generator: ScriptGenerator, store: SeriesDraftStore): SeriesDraft {
        publish(settings, context.getString(R.string.generation_status_concept), 0, 0)
        val count = settings.episodesPerSeries
        val concept = generator.concept(count)
        // Deux series ne peuvent pas partager un identifiant (donc un dossier).
        if (store.load(concept.id) != null) concept.id = "${concept.id}-${System.currentTimeMillis() % 100000}"
        val bible = generator.bible(concept, count)
        return SeriesDraft(concept = concept, bible = bible).also { store.save(it) }
    }

    /**
     * Ecrit le script, puis le fait relire. Un episode bloque par la revue est
     * reecrit (dans la limite des tentatives) ; s'il reste bloque, il est rendu
     * quand meme et le motif est consigne dans les points a revoir.
     */
    private suspend fun writeEpisode(
        settings: SettingsStore,
        generator: ScriptGenerator,
        store: SeriesDraftStore,
        draft: SeriesDraft,
        number: Int,
    ): EpisodeScript {
        publish(settings, context.getString(R.string.generation_status_writing, number, draft.targetEpisodeCount), 0, 0)
        val beat = draft.bible.beats[number - 1]
        val previous = draft.bible.beats.getOrNull(number - 2)

        var script = generator.episode(draft.concept, beat, previous)
        if (settings.safetyReviewEnabled) {
            for (round in 0..REVIEW_REWRITES) {
                val verdict = generator.review(draft.concept, script)
                val blockers = verdict.blockers()
                verdict.concerns.forEach { draft.issues += "Episode $number : $it" }
                if (blockers.isEmpty()) break
                if (round == REVIEW_REWRITES) {
                    draft.issues += "Episode $number toujours signale : ${blockers.joinToString(", ")}"
                } else {
                    script = generator.episode(draft.concept, beat, previous)
                }
            }
        }
        draft.episodes.removeAll { it.number == number }
        draft.episodes += script
        draft.episodes.sortBy { it.number }
        store.save(draft)
        return script
    }

    private suspend fun fail(settings: SettingsStore, message: String): Result {
        settings.lastStatus = message
        return Result.failure(workDataOf(KEY_MESSAGE to message))
    }

    /** Deplie les causes imbriquees (notamment ExceptionInInitializerError). */
    private fun describeError(error: Throwable): String {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < 6 && chain.none { it === current }) {
            chain += current
            current = current.cause
        }
        val causeText = chain.joinToString(" -> ") { cause ->
            val detail = cause.message?.takeIf { it.isNotBlank() }
            if (detail == null) cause.javaClass.simpleName else "${cause.javaClass.simpleName}: $detail"
        }
        val location = chain.asSequence()
            .flatMap { it.stackTrace.asSequence() }
            .firstOrNull { it.className.startsWith("com.jedrama.fork.") }
            ?.let { " [${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}]" }
            .orEmpty()
        return causeText + location
    }

    /** Publie l'etat vers l'UI, les preferences et la notification d'un coup. */
    private suspend fun publish(settings: SettingsStore, message: String, done: Int, total: Int) {
        settings.lastStatus = message
        setProgress(workDataOf(KEY_MESSAGE to message, KEY_DONE to done, KEY_TOTAL to total))
        // Sans permission de notification, l'avant-plan echoue : le travail
        // continue, limite a ~10 minutes par execution, et la reprise suit.
        try {
            setForeground(foregroundInfo(message, done, total))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+, arriere-plan).
        }
    }

    private fun foregroundInfo(message: String, done: Int, total: Int): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.generation_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.generation_channel_name))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK = "daily-series-generation"

        const val KEY_MESSAGE = "message"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        const val MAX_RUN_ATTEMPTS = 4
        private const val REVIEW_REWRITES = 1
        private const val MAX_NEW_SERIES_PER_RUN = 2
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 4201

        private val running = Mutex()

        fun messageOf(data: Data): String? = data.getString(KEY_MESSAGE)
    }
}

