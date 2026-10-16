package com.dramaforge.app.generation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit

/**
 * Etat de la generation tel que l'ecran Parametres doit le montrer.
 *
 * `message` porte aussi bien la progression que la cause d'un echec : sans
 * cela, un echec se traduisait par un bouton qui ne faisait visiblement rien.
 */
data class GenerationState(
    val running: Boolean = false,
    val queued: Boolean = false,
    val failed: Boolean = false,
    val message: String? = null,
    val done: Int = 0,
    val total: Int = 0,
) {
    val hasProgress: Boolean get() = total > 0 && done in 1..total
}

/** Planification du job quotidien et declenchement manuel. */
object GenerationScheduler {

    // Le reseau est indispensable ; la batterie non critique evite de vider le
    // telephone sur une serie de cinquante appels.
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun enableDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailySeriesWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailySeriesWorker.UNIQUE_WORK,
            // UPDATE et non KEEP : un changement de modele ou d'effort doit
            // s'appliquer sans attendre.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disableDaily(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DailySeriesWorker.UNIQUE_WORK)
    }

    /**
     * « Generer maintenant » : meme worker, donc meme logique de reprise.
     *
     * REPLACE et non KEEP. Avec KEEP, une execution precedente restee en file
     * (echec en attente de nouvelle tentative, contrainte reseau non remplie)
     * absorbait silencieusement chaque nouvel appui : le bouton semblait mort.
     */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<DailySeriesWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelNow(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK)
    }

    /** Etat combine du declenchement manuel et du job quotidien. */
    fun state(context: Context): Flow<GenerationState> {
        val manager = WorkManager.getInstance(context)
        return combine(
            manager.getWorkInfosForUniqueWorkFlow(MANUAL_WORK),
            manager.getWorkInfosForUniqueWorkFlow(DailySeriesWorker.UNIQUE_WORK),
        ) { manual, daily -> stateOf(manual + daily) }
    }

    /**
     * Une execution en cours prime sur tout ; sinon on montre le dernier
     * resultat, echec compris.
     */
    internal fun stateOf(infos: List<WorkInfo>): GenerationState {
        if (infos.isEmpty()) return GenerationState()

        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running != null) {
            return GenerationState(
                running = true,
                message = DailySeriesWorker.messageOf(running.progress),
                done = running.progress.getInt(DailySeriesWorker.KEY_DONE, 0),
                total = running.progress.getInt(DailySeriesWorker.KEY_TOTAL, 0),
            )
        }

        val pending = infos.firstOrNull {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
        }
        if (pending != null) return GenerationState(queued = true)

        val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
        if (failed != null) {
            return GenerationState(
                failed = true,
                message = DailySeriesWorker.messageOf(failed.outputData),
            )
        }

        val succeeded = infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
        return GenerationState(message = succeeded?.let { DailySeriesWorker.messageOf(it.outputData) })
    }

    private const val MANUAL_WORK = "manual-series-generation"
}
