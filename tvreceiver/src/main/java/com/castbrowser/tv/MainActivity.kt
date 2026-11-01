package com.castbrowser.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Pantalla de inicio en la TV: muestra que el receptor está listo y su IP. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private lateinit var overlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReceiverService.start(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#202124"))
            setPadding(80, 60, 80, 60)
        }
        root.addView(TextView(this).apply {
            text = "CastBrowser TV"
            textSize = 40f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        info = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#BDC1C6"))
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
        }
        root.addView(info)
        overlayButton = Button(this).apply {
            text = "Permitir abrir el reproductor automáticamente"
            setOnClickListener { askOverlay() }
        }
        root.addView(overlayButton)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        main.post(object : Runnable {
            override fun run() {
                refresh()
                if (!isFinishing) main.postDelayed(this, 2000)
            }
        })
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
    }

    private fun refresh() {
        val ip = localIp() ?: "sin Wi-Fi"
        val status = if (ReceiverService.running) "✅ Listo para recibir vídeos" else "⏳ Arrancando…"
        info.text = "$status\n\n" +
            "Nombre: ${ReceiverService.deviceName(this)}\n" +
            "Dirección: $ip:${ReceiverService.port}\n\n" +
            "En el móvil, abre CastBrowser, reproduce un vídeo y pulsa el botón de enviar.\n" +
            "Esta TV aparecerá como \"App CastBrowser TV\".\n" +
            "Puedes salir de esta pantalla: el receptor sigue funcionando y arranca solo al encender la TV."
        val needsOverlay = Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)
        overlayButton.visibility = if (needsOverlay) Button.VISIBLE else Button.GONE
    }

    /** En Android 10+ hace falta este permiso para abrir el vídeo con la app en segundo plano. */
    private fun askOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_: Exception) {
                info.text = info.text.toString() +
                    "\n\nEsta TV no deja dar el permiso. Si un vídeo no se abre solo, abre CastBrowser TV antes de enviarlo."
            }
        }
    }

    private fun localIp(): String? {
        try {
            @Suppress("DEPRECATION")
            val ip = getSystemService(WifiManager::class.java)?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
        } catch (_: Exception) {}
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }?.hostAddress
        } catch (_: Exception) { null }
    }
}
