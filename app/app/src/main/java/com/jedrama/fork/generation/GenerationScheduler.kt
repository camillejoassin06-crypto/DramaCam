package com.jedrama.fork.generation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jedrama.fork.settings.SettingsStore
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

    /**
     * Reseau pour le modele de texte et les images ; batterie et stockage
     * suffisants pour le rendu video. Wi-Fi et secteur selon les Parametres.
     */
    private fun constraints(settings: SettingsStore, manual: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.onlyOnUnmetered && !manual) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiresCharging(settings.onlyWhileCharging && !manual)
            .build()

    fun enableDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailySeriesWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints(SettingsStore(context), manual = false))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailySeriesWorker.UNIQUE_WORK,
            // UPDATE et non KEEP : un changement de reglage s'applique tout de suite.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disableDaily(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DailySeriesWorker.UNIQUE_WORK)
    }

    /**
     * Â« Generer maintenant Â» : meme worker, donc meme logique de reprise.
     *
     * REPLACE et non KEEP. Avec KEEP, une execution precedente restee en file
     * (echec en attente de nouvelle tentative, contrainte reseau non remplie)
     * absorbait silencieusement chaque nouvel appui : le bouton semblait mort.
     */
    fun runNow(context: Context) {
        // Declenchement manuel : ni secteur ni Wi-Fi exiges, l'utilisateur est la.
        val request = OneTimeWorkRequestBuilder<DailySeriesWorker>()
            .setConstraints(constraints(SettingsStore(context), manual = true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Arrete l'execution en cours, manuelle ou quotidienne. Annuler un travail
     * periodique le supprime : on le replanifie aussitot si la cadence est active.
     */
    fun cancelNow(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(MANUAL_WORK)
        manager.cancelUniqueWork(DailySeriesWorker.UNIQUE_WORK)
        if (SettingsStore(context).dailyEnabled) enableDaily(context)
    }

    /** Etat combine du declenchement manuel et du job quotidien. */
    fun state(context: Context): Flow<GenerationState> {
        val manager = WorkManager.getInstance(context)
        return combine(
            manager.getWorkInfosForUniqueWorkFlow(MANUAL_WORK),
            manager.getWorkInfosForUniqueWorkFlow(DailySeriesWorker.UNIQUE_WORK),
        ) { manual, daily -> stateOf(manual, daily) }
    }

    /**
     * Une execution en cours prime sur tout ; sinon on montre le dernier
     * resultat, echec compris.
     */
    internal fun stateOf(manual: List<WorkInfo>, daily: List<WorkInfo>): GenerationState {
        // Entre deux executions, un travail periodique reste ENQUEUED en
        // permanence : seul le manuel compte comme Â« en attente Â». Et un
        // periodique n'atteint jamais FAILED : son echec se lit dans lastStatus.
        val infos = manual + daily.filter { it.state == WorkInfo.State.RUNNING }
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

