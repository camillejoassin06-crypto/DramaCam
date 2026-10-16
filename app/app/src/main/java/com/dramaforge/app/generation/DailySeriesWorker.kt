package com.dramaforge.app.generation

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
import com.dramaforge.app.R
import com.dramaforge.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generation d'une serie complete, une fois par jour.
 *
 * Trois contraintes dictent la forme de ce worker :
 *
 * 1. Un Worker ordinaire est arrete au bout de ~10 minutes. Cinquante episodes
 *    demandent bien plus, d'ou l'execution en avant-plan (notification de
 *    progression) qui leve cette limite.
 * 2. Une interruption reste possible (batterie, processus tue, reseau). Chaque
 *    episode est donc persiste des qu'il est genere, et une execution suivante
 *    reprend a l'episode manquant plutot que de tout regenerer — ce qui
 *    eviterait surtout de tout refacturer.
 * 3. Tout etat et toute erreur sortent par `setProgress` et `Result.failure`,
 *    pas seulement dans les preferences : sans cela l'ecran n'a aucun moyen de
 *    dire ce qui s'est passe, et un echec ressemble a un bouton sans effet.
 */
class DailySeriesWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(context)
        if (!settings.hasApiKey) {
            return fail(settings, context.getString(R.string.generation_status_no_key))
        }

        val store = SeriesDraftStore(context)
        val generator = ScriptGenerator(
            apiKey = settings.apiKey,
            model = settings.model,
            effort = settings.effort,
            prompts = Prompts(context),
        )

        return try {
            withContext(Dispatchers.IO) { generate(settings, store, generator) }
        } catch (error: ScriptGenerator.GenerationException) {
            val message = error.message.orEmpty()
            // Reessayer une cle refusee ou une requete invalide ne ferait que
            // refacturer le meme echec, chaque jour, en silence.
            if (!error.retryable) return fail(settings, message)
            if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
                return fail(
                    settings,
                    context.getString(R.string.generation_status_gave_up, runAttemptCount, message),
                )
            }
            settings.lastStatus = message
            Result.retry()
        } catch (error: Exception) {
            // Filet de securite : une exception inattendue ne doit pas
            // disparaitre sans laisser de trace lisible a l'ecran.
            fail(settings, "${error.javaClass.simpleName} : ${error.message}")
        }
    }

    private suspend fun fail(settings: SettingsStore, message: String): Result {
        settings.lastStatus = message
        return Result.failure(workDataOf(KEY_MESSAGE to message))
    }

    private suspend fun generate(
        settings: SettingsStore,
        store: SeriesDraftStore,
        generator: ScriptGenerator,
    ): Result {
        val episodeCount = settings.episodesPerSeries

        // Reprise d'une serie inachevee, sinon nouveau concept + bible.
        val draft = store.pending() ?: run {
            publish(settings, context.getString(R.string.generation_status_concept), 0, episodeCount)
            val concept = generator.concept(episodeCount)
            val bible = generator.bible(concept, episodeCount)
            SeriesDraft(concept = concept, bible = bible).also { store.save(it) }
        }

        while (draft.episodes.size < draft.targetEpisodeCount) {
            if (isStopped) {
                val message = context.getString(
                    R.string.generation_status_interrupted,
                    draft.episodes.size,
                    draft.targetEpisodeCount,
                )
                settings.lastStatus = message
                return Result.retry()
            }

            val number = draft.nextEpisodeNumber
            val beat = draft.bible.beats[number - 1]
            val previous = draft.bible.beats.getOrNull(number - 2)

            val script = generator.episode(draft.concept, beat, previous)

            if (settings.safetyReviewEnabled) {
                val verdict = generator.review(draft.concept, script)
                val blockers = verdict.blockers()
                if (blockers.isNotEmpty()) {
                    draft.issues += "Episode $number bloque : ${blockers.joinToString(", ")}"
                }
                verdict.concerns.forEach { draft.issues += "Episode $number : $it" }
            }

            draft.episodes += script
            store.save(draft)

            publish(
                settings,
                context.getString(
                    R.string.generation_status_progress,
                    draft.episodes.size,
                    draft.targetEpisodeCount,
                ),
                draft.episodes.size,
                draft.targetEpisodeCount,
            )
        }

        draft.completedAt = System.currentTimeMillis()
        store.save(draft)
        val message = context.getString(
            R.string.generation_status_done,
            draft.concept.title,
            draft.episodes.size,
        )
        settings.lastStatus = message
        return Result.success(workDataOf(KEY_MESSAGE to message))
    }

    /** Publie l'etat vers l'UI, les preferences et la notification d'un coup. */
    private suspend fun publish(
        settings: SettingsStore,
        message: String,
        done: Int,
        total: Int,
    ) {
        settings.lastStatus = message
        setProgress(
            workDataOf(
                KEY_MESSAGE to message,
                KEY_DONE to done,
                KEY_TOTAL to total,
            ),
        )
        // Sans permission de notification (Android 13+ sans accord), l'avant-plan
        // echoue : la generation continue, simplement limitee a ~10 minutes par
        // execution, et la reprise prend le relais.
        runCatching { setForeground(foregroundInfo(message, done, total)) }
    }

    private fun foregroundInfo(message: String, done: Int, total: Int): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.generation_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.generation_channel_name))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK = "daily-series-generation"

        /** Clefs de sortie et de progression, lues par l'ecran Parametres. */
        const val KEY_MESSAGE = "message"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        /** Au-dela, on abandonne avec un message plutot que de boucler sans fin. */
        const val MAX_RUN_ATTEMPTS = 4
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 4201

        fun messageOf(data: Data): String? = data.getString(KEY_MESSAGE)
    }
}
