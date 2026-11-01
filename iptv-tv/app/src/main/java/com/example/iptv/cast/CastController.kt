package com.example.iptv.cast

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.iptv.data.Channel
import com.example.iptv.data.UA
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address

/**
 * Envío a Chromecast (Google Cast). Dos modos:
 *  - Retransmitir por el móvil (por defecto): el móvil descarga el vídeo —por la VPN/proxy que tenga— y se lo
 *    sirve al Chromecast por la Wi-Fi local, con CORS.
 *  - Directo: se le da la URL original al Chromecast, que la descarga él mismo.
 *
 * Antes de enviar se "sonda" el canal para saber qué formato sirve de verdad (HLS, MPEG-TS directo…):
 * el Chromecast solo reproduce HLS, y declararle un formato equivocado deja la pantalla sin vídeo.
 */
object CastController {
    var available by mutableStateOf(false)
        private set
    var connectedName by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
        private set
    var relayEnabled by mutableStateOf(true)
        private set

    private var appCtx: Context? = null
    private var castContext: CastContext? = null
    private var relay: CastRelay? = null
    private val main = Handler(Looper.getMainLooper())
    private var loadToken = 0
    private var statusOwner: RemoteMediaClient? = null

    private enum class Kind { HLS, TS, OTHER, ERROR }
    private class Probe(val url: String, val kind: Kind, val http: Int, val contentType: String?)

    /** Solo en móvil/tablet con Google Play Services. En Android TV / Fire TV no se llama o no está disponible. */
    fun init(context: Context) {
        if (castContext != null) return
        val app = context.applicationContext
        appCtx = app
        relayEnabled = app.getSharedPreferences("iptv", Context.MODE_PRIVATE).getBoolean("cast_relay", true)
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
                DebugLog.log("Google Play Services no disponible: Cast desactivado")
                return
            }
            val cc = CastContext.getSharedInstance(app)
            castContext = cc
            cc.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
            cc.sessionManager.currentCastSession?.let { attach(it) }
            available = true
            DebugLog.log("Cast listo (modo retransmisión=${relayEnabled}, VPN=${vpnActive(app)})")
        } catch (e: Exception) {
            available = false
            DebugLog.log("Error iniciando Cast: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun updateRelayEnabled(value: Boolean) {
        relayEnabled = value
        appCtx?.getSharedPreferences("iptv", Context.MODE_PRIVATE)?.edit()?.putBoolean("cast_relay", value)?.apply()
        DebugLog.log("Modo retransmisión por el móvil: $value")
    }

    /** Envía el canal al Chromecast conectado. [client] es el cliente HTTP del vídeo (con el proxy si está activo). */
    fun load(channel: Channel, client: OkHttpClient) {
        val ctx = appCtx ?: return
        if (castContext?.sessionManager?.currentCastSession?.remoteMediaClient == null) return
        error = null
        status = "Preparando el canal…"
        val token = ++loadToken
        DebugLog.log("── Enviar «${channel.name}» · ${DebugLog.redact(channel.url)}")
        Thread {
            val probe = resolve(channel.url, client)
            main.post { if (token == loadToken) deliver(ctx, channel, probe, client) }
        }.start()
    }

    fun disconnect() {
        DebugLog.log("Desconectando del Chromecast")
        loadToken++
        castContext?.sessionManager?.endCurrentSession(true)
        connectedName = null
        status = null
        stopRelay()
    }

    // ───────────── sondeo del formato ─────────────
    private fun resolve(url: String, client: OkHttpClient): Probe {
        var first: Probe? = null
        for (candidate in candidates(url)) {
            val p = probe(candidate, client)
            DebugLog.log("sonda ${DebugLog.redact(candidate)} → ${p.kind} http=${p.http} tipo=${p.contentType}")
            if (p.kind == Kind.HLS) return p
            if (first == null) first = p
        }
        return first!!
    }

    /** La dirección original y variantes que suelen ser HLS: Xtream (`/live/u/p/id.m3u8`) y `.ts`/sin extensión → `.m3u8`. */
    private fun candidates(url: String): List<String> {
        val base = url.substringBefore('?')
        val query = url.substring(base.length)
        val last = base.substringAfterLast('/')
        val swapped = when {
            base.endsWith(".ts", ignoreCase = true) -> base.dropLast(3) + ".m3u8" + query
            !last.contains('.') && last.isNotEmpty() -> "$base.m3u8$query"
            else -> null
        }
        return listOfNotNull(url, xtreamHls(url), swapped).distinct()
    }

    /** Muchas listas traen URLs Xtream en TS (`…/user/pass/123` o `.ts`); el mismo canal suele estar en HLS. */
    private fun xtreamHls(url: String): String? {
        val m = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""").find(url.substringBefore('?')) ?: return null
        val (base, user, pass, id) = m.destructured
        return "$base/live/$user/$pass/$id.m3u8"
    }

    private fun probe(url: String, client: OkHttpClient): Probe {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
            client.newCall(req).execute().use { r ->
                val ct = r.header("Content-Type")
                if (!r.isSuccessful) {
                    Probe(url, Kind.ERROR, r.code, ct)
                } else {
                    val src = r.body!!.source()
                    src.request(1024)
                    val n = minOf(1024L, src.buffer.size).toInt()
                    val head = src.buffer.snapshot(n)
                    val kind = when {
                        head.utf8().contains("#EXTM3U") || ct?.contains("mpegurl", ignoreCase = true) == true -> Kind.HLS
                        n > 0 && head[0].toInt() == 0x47 && (n < 189 || head[188].toInt() == 0x47) -> Kind.TS
                        ct?.contains("mp2t", ignoreCase = true) == true -> Kind.TS
                        else -> Kind.OTHER
                    }
                    Probe(r.request.url.toString(), kind, r.code, ct)
                }
            }
        } catch (e: Exception) {
            DebugLog.log("sonda falló: ${e.javaClass.simpleName}: ${e.message}")
            Probe(url, Kind.ERROR, -1, null)
        }
    }

    // ───────────── envío ─────────────
    private fun deliver(ctx: Context, channel: Channel, p: Probe, client: OkHttpClient) {
        status = null
        val remote = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        if (p.kind == Kind.ERROR) {
            error = if (p.http > 0) "El servidor del canal respondió HTTP ${p.http}"
            else "No se pudo conectar con el canal (revisa tu VPN/proxy)"
            return
        }
        val convertTs = p.kind == Kind.TS
        if (convertTs && !relayEnabled) {
            error = "Este canal es MPEG-TS directo: activa «Retransmitir por el móvil» para convertirlo a HLS."
            DebugLog.log("canal TS directo con la retransmisión desactivada")
            return
        }

        val contentType = if (p.kind == Kind.HLS || convertTs) "application/x-mpegURL" else (p.contentType?.substringBefore(';') ?: "video/mp4")
        val contentUrl = if (relayEnabled) {
            val ip = localLanIp(ctx)
            DebugLog.log("IP local del móvil: $ip · VPN=${vpnActive(ctx)}")
            if (ip == null) { error = "No se encontró la red Wi-Fi del móvil"; return }
            val r = ensureRelay(ctx, client) ?: return
            if (convertTs) {
                DebugLog.log("canal MPEG-TS directo: se convierte a HLS en el móvil")
                r.tsUrl(ip, r.startTs(p.url))
            } else r.urlFor(ip, p.url)
        } else p.url

        DebugLog.log("load: tipo=$contentType retransmisión=$relayEnabled tsAHls=$convertTs → ${DebugLog.redact(contentUrl)}")
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            putString(MediaMetadata.KEY_TITLE, channel.name)
            putString(MediaMetadata.KEY_SUBTITLE, channel.group)
        }
        val info = MediaInfo.Builder(contentUrl)
            .setContentUrl(contentUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(contentType)
            .setMetadata(meta)
            .build()
        remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
            .setResultCallback { res ->
                DebugLog.log("load → código=${res.status.statusCode} ${res.status.statusMessage ?: ""} datos=${res.customData}")
                if (!res.status.isSuccess) error = "El Chromecast rechazó el canal (código ${res.status.statusCode})"
            }
    }

    private fun ensureRelay(ctx: Context, client: OkHttpClient): CastRelay? {
        relay?.let { it.client = client; return it }
        return try {
            CastRelay(client).also {
                it.start(30_000, true)
                relay = it
                ContextCompat.startForegroundService(ctx, Intent(ctx, CastRelayService::class.java))
                DebugLog.log("servidor local en el puerto ${it.listeningPort}")
            }
        } catch (e: Exception) {
            error = "No se pudo iniciar el servidor local: ${e.message}"
            DebugLog.log("servidor local falló: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
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

    // ───────────── sesión y estado del receptor ─────────────
    private fun attach(s: CastSession) {
        connectedName = s.castDevice?.friendlyName ?: "Chromecast"
        DebugLog.log(
            "Sesión Cast: ${s.castDevice?.friendlyName} · modelo=${s.castDevice?.modelName} · " +
                "firmware=${s.castDevice?.deviceVersion}",
        )
        val rc = s.remoteMediaClient ?: return
        if (rc !== statusOwner) {
            statusOwner?.unregisterCallback(statusCallback)
            rc.registerCallback(statusCallback)
            statusOwner = rc
        }
    }

    private val statusCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val st = castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.mediaStatus ?: return
            DebugLog.log("Chromecast: estado=${stateName(st.playerState)} motivoParada=${idleName(st.idleReason)}")
            if (st.playerState == MediaStatus.PLAYER_STATE_IDLE && st.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                error = "El Chromecast no pudo reproducir este canal. Comparte el registro para diagnosticarlo."
            } else if (st.playerState == MediaStatus.PLAYER_STATE_PLAYING) {
                error = null
            }
        }
    }

    private fun stateName(s: Int) = when (s) {
        MediaStatus.PLAYER_STATE_IDLE -> "PARADO"
        MediaStatus.PLAYER_STATE_PLAYING -> "REPRODUCIENDO"
        MediaStatus.PLAYER_STATE_PAUSED -> "PAUSA"
        MediaStatus.PLAYER_STATE_BUFFERING -> "CARGANDO"
        MediaStatus.PLAYER_STATE_LOADING -> "PREPARANDO"
        else -> "DESCONOCIDO($s)"
    }

    private fun idleName(r: Int) = when (r) {
        MediaStatus.IDLE_REASON_NONE -> "ninguno"
        MediaStatus.IDLE_REASON_FINISHED -> "terminó"
        MediaStatus.IDLE_REASON_CANCELED -> "cancelado"
        MediaStatus.IDLE_REASON_INTERRUPTED -> "interrumpido"
        MediaStatus.IDLE_REASON_ERROR -> "ERROR"
        else -> "?($r)"
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) { DebugLog.log("Sesión Cast: iniciando…") }
        override fun onSessionStarted(s: CastSession, sessionId: String) { attach(s) }
        override fun onSessionStartFailed(s: CastSession, code: Int) {
            DebugLog.log("Sesión Cast: fallo al iniciar (código $code)")
            error = "No se pudo conectar con el Chromecast (código $code)"
            connectedName = null
        }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionEnded(s: CastSession, code: Int) {
            DebugLog.log("Sesión Cast: terminada (código $code)")
            connectedName = null
            stopRelay()
        }
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { attach(s) }
        override fun onSessionResumeFailed(s: CastSession, code: Int) {
            DebugLog.log("Sesión Cast: no se pudo reanudar (código $code)")
            connectedName = null
        }
        override fun onSessionSuspended(s: CastSession, reason: Int) { DebugLog.log("Sesión Cast: suspendida (motivo $reason)") }
    }
}
