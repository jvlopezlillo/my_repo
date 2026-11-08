package com.futmondo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.futmondo.core.FutmondoApi
import com.futmondo.core.FutmondoException
import com.futmondo.core.History
import com.futmondo.core.HistoryKind
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Revisión diaria: SOLO LEE y avisa. Nunca pone a la venta, puja ni cambia la alineación;
 * esas decisiones las tomas tú dentro de la app.
 */
object DailyReview {
    private const val WORK = "daily_review"
    private const val CHANNEL = "daily_review"

    /** Programa (o reprograma) la revisión para la hora local dada. Android no garantiza la hora exacta. */
    fun schedule(context: Context, hour: Int) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val delay = next.timeInMillis - now.timeInMillis
        val req = PeriodicWorkRequest.Builder(DailyWorker::class.java, 24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun notify(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Revisión diaria", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(1, n) } catch (e: SecurityException) { /* sin permiso de notificaciones */ }
    }
}

class DailyWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val session = SessionStore(ctx).load() ?: return Result.success() // sin sesión: nada que hacer
        val api = FutmondoApi(AndroidHttp(), session)
        val c = Controller(ctx, api)
        return try {
            val a = c.analyze(HistoryKind.AUTOMATICA, "(automática) ")
            val m = c.reviewMarket(record = false)
            val sells = m.sells.filter { it.preselected }.map { it.player.name }
            val buys = m.buys.map { it.player.name }
            c.data.addHistory(HistoryKind.AUTOMATICA, "(automática) " + History.marketSummary(m))
            val text = buildString {
                append("Jornada ${a.round}: mejor formación ${a.best.formation}")
                if (a.current.strategy != a.best.formation) append(" (tienes ${a.current.strategy})")
                append(".\n")
                append("Posibles ventas: ${sells.joinToString().ifEmpty { "ninguna" }}.\n")
                append("Fichajes a vigilar: ${buys.joinToString().ifEmpty { "ninguno" }}.\n")
                append("Abre la app para decidir; no se ha hecho ningún cambio.")
            }
            DailyReview.notify(ctx, "Revisión diaria de Futmondo", text)
            Result.success()
        } catch (e: FutmondoException) {
            if (e.isAuth) DailyReview.notify(ctx, "Futmondo: sesión caducada", "Abre la app y vuelve a entrar para seguir con la revisión diaria.")
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.success() // p. ej. la API cambió: no reintentar en bucle
        }
    }
}
