package com.example.directtransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano: mantiene vivo el proceso (y los bloqueos de CPU/Wi-Fi) mientras hay
 * una transferencia en curso, con una notificación de progreso y acciones Aceptar/Rechazar/Cancelar.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var nm: NotificationManager
    private lateinit var engine: TransferEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        engine = TransferEngine.get(application)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Transferencias", NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Peticiones y resultados", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACCEPT -> engine.answerRequest(true)
            ACTION_REJECT -> engine.answerRequest(false)
            ACTION_CANCEL -> engine.cancelTransfer()
        }
        val n = build(engine.state.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, n)
        }
        acquireLocks()

        if (job == null) {
            job = scope.launch {
                var wasBusy = false
                engine.state
                    .map { Triple(it.busy, it.progress?.percent, it.incoming) to it }
                    .distinctUntilChanged { a, b -> a.first == b.first && a.second.status == b.second.status }
                    .collect { (_, s) ->
                        if (s.busy) {
                            wasBusy = true
                            nm.notify(ID, build(s))
                        } else {
                            if (wasBusy) postResult(s)
                            stopForeground(true)
                            stopSelf()
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(s: UiState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sending = s.screen == Screen.SEND
        val req = s.incoming
        val b = NotificationCompat.Builder(this, if (req != null) CHANNEL_ALERT else CHANNEL)
            .setSmallIcon(if (sending) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(if (sending) "Enviando archivos" else "Recibiendo archivos")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(if (req != null) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)

        if (req != null) {
            b.setContentTitle("Transferencia entrante")
                .setContentText("«${req.senderName}»: ${req.fileCount} archivo(s), ${formatBytes(req.totalBytes)}")
                .addAction(0, "Aceptar", action(ACTION_ACCEPT))
                .addAction(0, "Rechazar", action(ACTION_REJECT))
        } else {
            val p = s.progress
            if (p != null) {
                b.setContentText("${p.percent} % · ${p.fileIndex}/${p.fileCount} · ${formatBytes(p.bytesPerSecond)}/s")
                    .setProgress(100, p.percent, false)
            } else {
                b.setContentText(s.status).setProgress(0, 0, true)
            }
            b.addAction(0, "Cancelar", action(ACTION_CANCEL))
        }
        return b.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(), Intent(this, TransferService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun postResult(s: UiState) {
        val text = when {
            s.finished -> s.status
            s.error != null -> s.error
            else -> return
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            ID_RESULT,
            NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (s.finished) "Transferencia completada" else "Transferencia interrumpida")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DirectTransfer:cpu").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DirectTransfer:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "transfer"
        private const val CHANNEL_ALERT = "transfer_alert"
        private const val ID = 1001
        private const val ID_RESULT = 1002
        const val ACTION_ACCEPT = "com.example.directtransfer.ACCEPT"
        const val ACTION_REJECT = "com.example.directtransfer.REJECT"
        const val ACTION_CANCEL = "com.example.directtransfer.CANCEL"
    }
}
