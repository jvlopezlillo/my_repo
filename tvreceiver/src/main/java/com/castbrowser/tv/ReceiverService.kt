package com.castbrowser.tv

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/**
 * Servicio en primer plano que mantiene el receptor siempre escuchando
 * (también tras reiniciar la TV) y lo anuncia en la Wi-Fi por mDNS.
 */
class ReceiverService : Service() {
    private var server: ReceiverServer? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (server == null) {
            val name = deviceName(this)
            val id = deviceId(this)
            server = try { ReceiverServer(applicationContext, name, id) } catch (_: Exception) { null }
            server?.let { advertise(name, id, it.port) }
            running = server != null
            port = server?.port ?: 0
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Receptor", NotificationManager.IMPORTANCE_MIN))
        }
        val n = builder()
            .setSmallIcon(R.drawable.icon)
            .setContentTitle("CastBrowser TV listo para recibir vídeos")
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)

    private fun advertise(name: String, id: String, port: Int) {
        val mgr = getSystemService(NsdManager::class.java) ?: return
        nsd = mgr
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_castbrowser._tcp"
            setPort(port)
            setAttribute("fn", name)
            setAttribute("id", id)
            setAttribute("md", Build.MODEL)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(si: NsdServiceInfo?, errorCode: Int) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceRegistered(si: NsdServiceInfo?) {}
            override fun onServiceUnregistered(si: NsdServiceInfo?) {}
        }
        registration = l
        try { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { registration?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        server?.close()
        server = null
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "receiver"
        @Volatile var running = false; private set
        @Volatile var port = 0; private set

        fun start(ctx: Context) {
            val i = Intent(ctx, ReceiverService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {}
        }

        fun deviceName(ctx: Context): String =
            Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                ?: Build.MODEL

        @SuppressLint("HardwareIds")
        fun deviceId(ctx: Context): String =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL
    }
}

/** Arranca el receptor al encender la TV. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReceiverService.start(context)
    }
}
