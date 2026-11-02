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
import com.google.android.gms.cast.CastStatusCodes
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
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Envío a Chromecast (Google Cast).
 *
 * Dos caminos para un canal HLS:
 *  - DIRECTO: se le da la URL al Chromecast, que la descarga él mismo (rápido, sin gastar batería del móvil).
 *  - POR EL MÓVIL: el móvil descarga el vídeo —por la VPN/proxy que tenga— y se lo sirve al Chromecast por la
 *    Wi-Fi local, con CORS. Imprescindible para MPEG-TS directo (se convierte a HLS) y cuando hay VPN/proxy.
 *
 * Modo automático: se empieza por DIRECTO (salvo VPN/proxy/TS/"forzar") y, si el Chromecast no avanza, se prueba
 * el otro camino. Se recuerda qué Chromecast no consigue conectar con el móvil para no volver a intentarlo.
 */
object CastController {
    var available by mutableStateOf(false)
        private set
    var connectedName by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
        private set
    var forceRelay by mutableStateOf(false)
        private set

    private var appCtx: Context? = null
    private var castContext: CastContext? = null
    private var relay: CastRelay? = null
    private val main = Handler(Looper.getMainLooper())
    private var loadToken = 0
    private var statusOwner: RemoteMediaClient? = null

    private enum class Kind { HLS, TS, OTHER, ERROR }
    private enum class Mode { DIRECT, RELAY }
    private class Probe(val url: String, val kind: Kind, val http: Int, val contentType: String?)

    /** Estado de un envío: qué caminos se han probado y si el Chromecast ha avanzado. */
    private class Attempt(val token: Int, val channel: Channel, val probe: Probe, val client: OkHttpClient) {
        var mode = Mode.DIRECT
        var triedDirect = false
        var triedRelay = false
        var progressed = false
        var finished = false
    }
    private var attempt: Attempt? = null

    private fun prefs() = appCtx?.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    /** Solo en móvil/tablet con Google Play Services. En Android TV / Fire TV no se llama o no está disponible. */
    fun init(context: Context) {
        if (castContext != null) return
        val app = context.applicationContext
        appCtx = app
        forceRelay = prefs()?.getBoolean("cast_force_relay", false) ?: false
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
            DebugLog.log("Cast listo (retransmisión forzada=$forceRelay, VPN=${vpnActive(app)})")
        } catch (e: Exception) {
            available = false
            DebugLog.log("Error iniciando Cast: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun updateForceRelay(value: Boolean) {
        forceRelay = value
        prefs()?.edit()?.putBoolean("cast_force_relay", value)?.apply()
        DebugLog.log("Retransmisión por el móvil forzada: $value")
    }

    /**
     * Envía el canal al Chromecast conectado. [client] es el cliente HTTP del vídeo (con el proxy si está activo);
     * [viaProxy] indica que el usuario quiere que el vídeo salga por el proxy de la app (entonces se usa el móvil de puente).
     */
    fun load(channel: Channel, client: OkHttpClient, viaProxy: Boolean = false) {
        val ctx = appCtx ?: return
        if (castContext?.sessionManager?.currentCastSession?.remoteMediaClient == null) return
        error = null
        status = "Preparando el canal…"
        val token = ++loadToken
        attempt = null
        relay?.stopTs()
        DebugLog.log("── Enviar «${channel.name}» · ${DebugLog.redact(channel.url)}")
        Thread {
            val probe = resolve(channel.url, client)
            main.post { if (token == loadToken) deliver(ctx, channel, probe, client, token, viaProxy) }
        }.start()
    }

    fun disconnect() {
        DebugLog.log("Desconectando del Chromecast")
        loadToken++
        attempt = null
        castContext?.sessionManager?.endCurrentSession(true)
        connectedName = null
        status = null
        stopRelay()
    }

    // ───────────── sondeo del formato ─────────────
    private fun resolve(url: String, client: OkHttpClient): Probe {
        var first: Probe? = null
        var original: Probe? = null
        for (candidate in candidates(url)) {
            var p = probe(candidate, client)
            if (p.http == 403 || p.http == 429) {
                DebugLog.log("sonda: HTTP ${p.http}, se reintenta en 3 s (¿límite de conexiones del proveedor?)")
                Thread.sleep(3000)
                p = probe(candidate, client)
            }
            if (candidate == url) original = p
            DebugLog.log("sonda ${DebugLog.redact(candidate)} → ${p.kind} http=${p.http} tipo=${p.contentType}")
            if (p.kind == Kind.HLS) return p
            if (first == null) first = p
        }
        return original ?: first!!
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
        // Si parece TS o no tiene extensión, lo más probable es que exista versión HLS: se prueba antes que el original,
        // para no abrir primero el flujo TS (los proveedores suelen limitar las conexiones simultáneas).
        val alternatives = listOfNotNull(xtreamHls(url), swapped)
        return (alternatives + url).distinct()
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
            val quick = client.newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build()
            quick.newCall(req).execute().use { r ->
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
    private fun deliver(ctx: Context, channel: Channel, p: Probe, client: OkHttpClient, token: Int, viaProxy: Boolean) {
        status = null
        if (p.kind == Kind.ERROR) {
            error = if (p.http > 0) "El servidor del canal respondió HTTP ${p.http}"
            else "No se pudo conectar con el canal (revisa tu VPN/proxy)"
            return
        }
        val att = Attempt(token, channel, p, client)
        attempt = att
        val convertTs = p.kind == Kind.TS
        val wantsRelay = forceRelay || viaProxy // la VPN por sí sola no basta: con VPN la sesión Cast funciona y el directo es más rápido
        val first = when {
            forceRelay -> Mode.RELAY                       // forzado: también sirve para volver a comprobar la red
            relayUnreachable() -> Mode.DIRECT              // ya sabemos que este Chromecast no llega al móvil
            convertTs || wantsRelay -> Mode.RELAY          // MPEG-TS: solo se puede convertir en el móvil
            else -> Mode.DIRECT
        }
        DebugLog.log("modo inicial: $first (forzado=$forceRelay proxy=$viaProxy vpn=${vpnActive(ctx)} ts=$convertTs inalcanzable=${relayUnreachable()})")
        send(att, first)
    }

    private fun send(att: Attempt, mode: Mode) {
        val ctx = appCtx ?: return
        val remote = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        val p = att.probe
        val convertTs = p.kind == Kind.TS
        att.mode = mode
        att.progressed = false

        val url: String
        if (mode == Mode.RELAY) {
            att.triedRelay = true
            val ip = localLanIp(ctx)
            DebugLog.log("IP local del móvil: $ip · VPN=${vpnActive(ctx)}")
            if (ip == null) { fail(att, "No se encontró la red Wi-Fi del móvil"); return }
            val r = ensureRelay(ctx, att.client) ?: run { att.finished = true; return }
            url = if (convertTs) {
                DebugLog.log("canal MPEG-TS directo: se convierte a HLS en el móvil")
                r.tsUrl(ip, r.startTs(p.url))
            } else r.urlFor(ip, p.url)
            status = "Enviando por el móvil…"
            val before = r.requestCount.get()
            main.postDelayed({
                if (attempt !== att || att.mode != Mode.RELAY || att.progressed || att.finished) return@postDelayed
                if (r.requestCount.get() != before) return@postDelayed // sí ha contactado: se deja avanzar
                DebugLog.log("el Chromecast no ha contactado con el móvil en 6 s (¿aislamiento de red / VLAN?)")
                markRelay(false)
                r.stopTs()
                if (!att.triedDirect) send(att, Mode.DIRECT) // HLS directo, o MPEG-TS crudo como último recurso
                else fail(att, noPlayMessage(att))
            }, 6_000)
        } else {
            att.triedDirect = true
            url = p.url
            // Si antes se probó el camino por el móvil, el receptor sigue ocupado con aquel intento unos ~47 s
            val slowStart = att.triedRelay
            status = if (slowStart) "El Chromecast está terminando el intento anterior; puede tardar hasta ~50 s…"
            else "Enviando directamente al Chromecast…"
            val wait = if (slowStart) 75_000L else 20_000L
            main.postDelayed({
                if (attempt !== att || att.mode != Mode.DIRECT || att.progressed || att.finished) return@postDelayed
                DebugLog.log("sin progreso en ${wait / 1000} s con el envío directo")
                if (!att.triedRelay && !relayUnreachable()) send(att, Mode.RELAY)
                else fail(att, noPlayMessage(att))
            }, wait)
        }

        val contentType = when {
            p.kind == Kind.HLS || (convertTs && mode == Mode.RELAY) -> "application/x-mpegURL" // TS convertido en el móvil = HLS
            convertTs -> "video/mp2t"                                                          // TS crudo enviado en directo
            else -> p.contentType?.substringBefore(';') ?: "video/mp4"
        }
        DebugLog.log("load [$mode]: tipo=$contentType → ${DebugLog.redact(url)}")
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            putString(MediaMetadata.KEY_TITLE, att.channel.name)
            putString(MediaMetadata.KEY_SUBTITLE, att.channel.group)
        }
        val info = MediaInfo.Builder(url)
            .setContentUrl(url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(contentType)
            .setMetadata(meta)
            .build()
        remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
            .setResultCallback { res ->
                DebugLog.log("load [$mode] → código=${res.status.statusCode} ${res.status.statusMessage ?: ""} datos=${res.customData}")
                if (!res.status.isSuccess && attempt === att) fail(att, "El Chromecast rechazó el canal (código ${res.status.statusCode})")
            }
    }

    private fun fail(att: Attempt, message: String) {
        att.finished = true
        status = null
        error = message
    }

    /** El Chromecast ha devuelto un error de reproducción: se prueba el otro camino si queda alguno. */
    private fun fallbackFromError(att: Attempt) {
        val ts = att.probe.kind == Kind.TS
        when {
            att.mode == Mode.DIRECT && !att.triedRelay && !relayUnreachable() -> {
                DebugLog.log("error en el envío directo: se prueba por el móvil")
                send(att, Mode.RELAY)
            }
            att.mode == Mode.RELAY && !att.triedDirect && !ts -> {
                DebugLog.log("error en el envío por el móvil: se prueba el directo")
                send(att, Mode.DIRECT)
            }
            else -> fail(att, noPlayMessage(att))
        }
    }

    private fun noPlayMessage(att: Attempt): String =
        if (att.probe.kind == Kind.TS) {
            "Este canal es MPEG-TS directo: el Chromecast no lo reproduce tal cual y no puede conectar con el móvil para convertirlo" +
                (if (notice != null) " (parece tener una VPN activa: permite la red local en ella o desconéctala)" else "") +
                ". Prueba «Duplicar pantalla» (Transmitir pantalla de Android) o un canal con versión HLS."
        } else "El Chromecast no pudo reproducir este canal. Comparte el registro para diagnosticarlo."

    private fun ensureRelay(ctx: Context, client: OkHttpClient): CastRelay? {
        relay?.let { it.client = client; return it }
        return try {
            CastRelay(client).also {
                it.start(30_000, true)
                relay = it
                ContextCompat.startForegroundService(ctx, Intent(ctx, CastRelayService::class.java))
                DebugLog.log("servidor local en el puerto ${it.listeningPort}")
                selfTest(ctx, it.listeningPort)
            }
        } catch (e: Exception) {
            error = "No se pudo iniciar el servidor local: ${e.message}"
            DebugLog.log("servidor local falló: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Comprueba que el servidor local acepta conexiones en la IP de la Wi-Fi (descarta que escuche solo en loopback). */
    private fun selfTest(ctx: Context, port: Int) {
        Thread {
            val ip = localLanIp(ctx)
            val ok = ip != null && try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 1500) }
                true
            } catch (e: Exception) { false }
            DebugLog.log("autoprueba del servidor local ($ip:$port): ${if (ok) "responde" else "NO responde"}")
        }.start()
    }

    private fun codeText(code: Int) = "$code (${CastStatusCodes.getStatusCodeString(code)})"

    /** Anota en el registro la red del móvil (para comparar con la del Chromecast). */
    fun logNetwork(ctx: Context) {
        @Suppress("DEPRECATION")
        val wi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        DebugLog.log("red del móvil: ip=${localLanIp(ctx)} VPN=${vpnActive(ctx)} wifi=${wi?.frequency} MHz ${wi?.linkSpeed} Mbps")
    }

    /** Consulta la información de red que publica el propio Chromecast (si la ofrece) para compararla con la del móvil. */
    fun inspectChromecast(name: String, ip: String) {
        notice = null
        Thread {
            try {
                val c = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
                c.newCall(Request.Builder().url("http://$ip:8008/setup/eureka_info?options=detail").build()).execute().use { r ->
                    if (!r.isSuccessful) {
                        DebugLog.log("Chromecast $ip: información de red no disponible (HTTP ${r.code})")
                        return@use
                    }
                    val found = LinkedHashMap<String, String>()
                    fun walk(o: JSONObject) {
                        for (k in o.keys()) {
                            val v = o.opt(k)
                            if (v is JSONObject) walk(v) else if (k in WANTED_KEYS && k !in found) found[k] = v.toString()
                        }
                    }
                    walk(JSONObject(r.body!!.string()))
                    DebugLog.log("Chromecast $ip: " + found.entries.joinToString(" ") { "${it.key}=${it.value}" })
                    // Si se identifica con una IP de otra red, casi seguro hay una VPN activa en el Chromecast.
                    val reported = found["ip_address"]
                    if (reported != null && reported != ip && reported.substringBeforeLast('.') != ip.substringBeforeLast('.')) {
                        val msg = "El Chromecast se identifica con la IP $reported, distinta de la de su Wi-Fi ($ip): probablemente tiene una " +
                            "VPN activa. Mientras esa VPN no permita la red local (LAN), no puede conectar con el móvil."
                        DebugLog.log(msg)
                        main.post {
                            notice = msg
                            markRelayFor(name, false)
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("Chromecast $ip: no se pudo leer su información de red (${e.javaClass.simpleName})")
            }
        }.start()
    }

    /** Aviso sobre el Chromecast elegido (p. ej. VPN activa en él). */
    var notice by mutableStateOf<String?>(null)
        private set

    private val WANTED_KEYS = setOf("ssid", "ip_address", "ethernet_connected", "signal_level", "wpa_state", "build_version")

    /** Dirección que se puede abrir desde otro dispositivo de la Wi-Fi para comprobar que llega al móvil. */
    var testUrl by mutableStateOf<String?>(null)
        private set

    fun startNetworkTest() {
        val ctx = appCtx ?: return
        val ip = localLanIp(ctx)
        if (ip == null) { testUrl = null; error = "No se encontró la red Wi-Fi del móvil"; return }
        val r = ensureRelay(ctx, OkHttpClient()) ?: return
        testUrl = "http://$ip:${r.listeningPort}/ping"
        DebugLog.log("prueba de red: abre ${testUrl} desde otro dispositivo de la misma Wi-Fi")
    }

    fun stopNetworkTest() {
        if (testUrl == null) return
        testUrl = null
        if (connectedName == null) stopRelay()
    }

    private fun stopRelay() {
        relay?.stop()
        relay = null
        appCtx?.let { it.stopService(Intent(it, CastRelayService::class.java)) }
    }

    // ───────────── memoria: Chromecast que no llegan al móvil ─────────────
    private fun relayUnreachable(): Boolean {
        val name = connectedName ?: return false
        return prefs()?.getStringSet("cast_relay_unreachable", emptySet())?.contains(name) == true
    }

    private fun markRelay(reachable: Boolean) {
        markRelayFor(connectedName ?: return, reachable)
    }

    private fun markRelayFor(name: String, reachable: Boolean) {
        val p = prefs() ?: return
        val set = (p.getStringSet("cast_relay_unreachable", emptySet()) ?: emptySet()).toMutableSet()
        val changed = if (reachable) set.remove(name) else set.add(name)
        if (changed) {
            p.edit().putStringSet("cast_relay_unreachable", set).apply()
            DebugLog.log(if (reachable) "«$name» sí llega al móvil" else "«$name» no llega al móvil: se usará el envío directo")
        }
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
            val att = attempt
            when {
                st.playerState == MediaStatus.PLAYER_STATE_PLAYING -> {
                    error = null
                    status = null
                    if (att != null && !att.finished) {
                        att.progressed = true
                        att.finished = true
                        DebugLog.log("reproduciendo [${att.mode}]")
                        if (att.mode == Mode.RELAY) markRelay(true)
                    }
                }
                st.playerState == MediaStatus.PLAYER_STATE_BUFFERING -> att?.progressed = true
                st.playerState == MediaStatus.PLAYER_STATE_IDLE && st.idleReason == MediaStatus.IDLE_REASON_ERROR -> {
                    if (att != null && !att.finished) fallbackFromError(att)
                    else error = "El Chromecast no pudo reproducir este canal. Comparte el registro para diagnosticarlo."
                }
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
            DebugLog.log("Sesión Cast: fallo al iniciar, código ${codeText(code)}")
            error = "No se pudo conectar con el Chromecast (código $code)"
            connectedName = null
        }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionEnded(s: CastSession, code: Int) {
            DebugLog.log("Sesión Cast: terminada, código ${codeText(code)}")
            notice = null
            connectedName = null
            attempt = null
            stopRelay()
        }
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { attach(s) }
        override fun onSessionResumeFailed(s: CastSession, code: Int) {
            DebugLog.log("Sesión Cast: no se pudo reanudar, código ${codeText(code)}")
            connectedName = null
        }
        override fun onSessionSuspended(s: CastSession, reason: Int) { DebugLog.log("Sesión Cast: suspendida, motivo ${codeText(reason)}") }
    }
}
