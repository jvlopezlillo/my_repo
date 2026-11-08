package com.futmondo.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.futmondo.core.FutmondoApi
import com.futmondo.core.FutmondoException
import com.futmondo.core.HistoryKind
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Aviso unas horas antes de que se bloquee la alineación (primer partido de la jornada): si tu once actual
 * es claramente peor que el recomendado, te lo notifica. Solo lee y avisa; no cambia nada.
 */
object LockAlert {
    private const val WORK = "lock_alert"
    const val HOURS_BEFORE = 3L
    const val MIN_IMPROVEMENT = 1.0

    fun schedule(context: Context, firstMatchMillis: Long) {
        val delay = firstMatchMillis - TimeUnit.HOURS.toMillis(HOURS_BEFORE) - System.currentTimeMillis()
        if (delay <= 0) return // ya es tarde para avisar con antelación
        val req = OneTimeWorkRequest.Builder(LockAlertWorker::class.java)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

class LockAlertWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val session = SessionStore(ctx).load() ?: return Result.success()
        val c = Controller(ctx, FutmondoApi(AndroidHttp(), session))
        if (!c.data.dailyEnabled) return Result.success()
        return try {
            val a = c.analyze(HistoryKind.AUTOMATICA, "(aviso de cierre) ")
            val imp = a.improvement
            if (imp != null && imp >= LockAlert.MIN_IMPROVEMENT) {
                val changes = a.best.starters.filter { it.player.id !in a.current.starterIds }.map { it.player.name }
                DailyReview.notify(
                    ctx, "Tu alineación se bloquea pronto", id = 2,
                    text = "El once recomendado da +${"%.1f".format(imp)} pts esperados más que el tuyo " +
                        "(${a.best.formation}). Entrarían: ${changes.joinToString().ifEmpty { "cambio de formación" }}. " +
                        "Cámbialo tú en Futmondo antes de que empiece la jornada.",
                )
            }
            Result.success()
        } catch (e: FutmondoException) {
            DailyReview.handleFailure(ctx, c.data, e)
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.success()
        }
    }
}
