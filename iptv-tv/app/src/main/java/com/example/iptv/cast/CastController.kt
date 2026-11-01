package com.example.iptv.cast

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.iptv.data.Channel
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import okhttp3.OkHttpClient
import java.net.Inet4Address

/**
 * Envío a Chromecast (Google Cast). Dos modos:
 *  - Retransmitir por el móvil (por defecto): el móvil descarga el vídeo —por la VPN/proxy que tenga— y se lo
 *    sirve al Chromecast por la Wi-Fi local, con CORS. Necesario si el servidor no permite CORS o si el
 *    Chromecast no puede acceder al origen con su propia IP.
 *  - Directo: se le da la URL original al Chromecast, que la descarga él mismo.
 */
object CastController {
    var available by mutableStateOf(false)
        private set
    var connectedName by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var relayEnabled by mutableStateOf(true)
        private set

    private var appCtx: Context? = null
    private var castContext: CastContext? = null
    private var relay: CastRelay? = null

    /** Solo en móvil/tablet con Google Play Services. En Android TV / Fire TV no se llama o no está disponible. */
    fun init(context: Context) {
        if (castContext != null) return
        val app = context.applicationContext
        appCtx = app
        relayEnabled = app.getSharedPreferences("iptv", Context.MODE_PRIVATE).getBoolean("cast_relay", true)
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) return
            val cc = CastContext.getSharedInstance(app)
            castContext = cc
            cc.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
            cc.sessionManager.currentCastSession?.let { connectedName = it.castDevice?.friendlyName ?: "Chromecast" }
            available = true
        } catch (e: Exception) {
            available = false
        }
    }

    fun updateRelayEnabled(value: Boolean) {
        relayEnabled = value
        appCtx?.getSharedPreferences("iptv", Context.MODE_PRIVATE)?.edit()?.putBoolean("cast_relay", value)?.apply()
    }

    /** Envía el canal al Chromecast conectado. [client] es el cliente HTTP del vídeo (con el proxy si está activo). */
    fun load(channel: Channel, client: OkHttpClient) {
        val ctx = appCtx ?: return
        val remote = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        error = null

        val isHls = channel.url.contains(".m3u8", ignoreCase = true)
        val contentUrl = if (relayEnabled) {
            val ip = localLanIp(ctx)
            if (ip == null) { error = "No se encontró la red Wi-Fi del móvil"; return }
            val r = relay?.also { it.client = client } ?: try {
                CastRelay(client).also {
                    it.start(30_000, true)
                    relay = it
                    ContextCompat.startForegroundService(ctx, Intent(ctx, CastRelayService::class.java))
                }
            } catch (e: Exception) {
                error = "No se pudo iniciar el servidor local: ${e.message}"
                return
            }
            r.urlFor(ip, channel.url)
        } else channel.url

        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            putString(MediaMetadata.KEY_TITLE, channel.name)
            putString(MediaMetadata.KEY_SUBTITLE, channel.group)
        }
        val info = MediaInfo.Builder(contentUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(if (isHls) "application/x-mpegURL" else "video/mp2t")
            .setMetadata(meta)
            .build()
        remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
    }

    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
        connectedName = null
        stopRelay()
    }

    private fun stopRelay() {
        relay?.stop()
        relay = null
        appCtx?.let { it.stopService(Intent(it, CastRelayService::class.java)) }
    }

    /** IP del móvil en la red local (Wi-Fi/Ethernet), ignorando la VPN. */
    private fun localLanIp(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val addr = cm.getLinkProperties(n)?.linkAddresses?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: continue
            return addr.hostAddress
        }
        return null
    }

    /** true si hay una VPN activa en el móvil (para avisar de la configuración de red local). */
    fun vpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStarted(s: CastSession, sessionId: String) {
            connectedName = s.castDevice?.friendlyName ?: "Chromecast"
        }
        override fun onSessionStartFailed(s: CastSession, code: Int) {
            error = "No se pudo conectar con el Chromecast (código $code)"
            connectedName = null
        }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionEnded(s: CastSession, code: Int) {
            connectedName = null
            stopRelay()
        }
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
            connectedName = s.castDevice?.friendlyName ?: "Chromecast"
        }
        override fun onSessionResumeFailed(s: CastSession, code: Int) { connectedName = null }
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }
}
