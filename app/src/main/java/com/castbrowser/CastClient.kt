package com.castbrowser

import org.json.JSONObject
import java.io.DataInputStream
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Estado de reproducción que informa el Chromecast. */
data class CastStatus(
    val playerState: String = "IDLE",
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Double = 1.0,
    val muted: Boolean = false,
    val idleReason: String? = null,
)

class CastException(msg: String) : Exception(msg)

/**
 * Cliente del protocolo Cast v2. Se conecta por TLS al puerto 8009 del
 * Chromecast y usa la app "Default Media Receiver" para reproducir el vídeo.
 *
 * @param rawSocket crea el socket TCP ya conectado (permite atarlo a la Wi-Fi
 *                  aunque haya una VPN activa).
 */
class CastClient(
    private val host: String,
    private val port: Int,
    private val rawSocket: (String, Int) -> Socket,
    private val onStatus: (CastStatus) -> Unit,
    private val onClosed: (String?) -> Unit,
    private val log: (String) -> Unit = {},
    private val loadTimeoutMs: Long = 60_000,
) : Renderer {
    @Volatile private var abortCheck: ((Long) -> String?)? = null
    override fun setAbortCheck(check: ((Long) -> String?)?) { abortCheck = check }

    companion object {
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        private const val SENDER = "sender-0"
        private const val RECEIVER = "receiver-0"

        private val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        /** Los Chromecast usan certificados autofirmados: no se validan. */
        fun tlsWrap(raw: Socket, host: String, port: Int): SSLSocket {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trustAll), SecureRandom())
            val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.startHandshake()
            return ssl
        }
    }

    private var socket: SSLSocket? = null
    private val requestId = AtomicInteger(1)
    private val waiters = ConcurrentHashMap<Int, LinkedBlockingQueue<JSONObject>>()
    private val receiverStatus = LinkedBlockingQueue<JSONObject>()
    private val mediaEvents = LinkedBlockingQueue<JSONObject>()
    /** true cuando el vídeo ya se está reproduciendo (no durante los intentos de carga). */
    @Volatile private var playing = false
    @Volatile private var closed = false

    @Volatile var transportId: String? = null; private set
    @Volatile var sessionId: String? = null; private set
    @Volatile var mediaSessionId: Int = -1; private set
    @Volatile override var lastStatus = CastStatus(); private set

    val isConnected: Boolean get() = !closed && socket?.isConnected == true

    override fun connect() {
        val raw = rawSocket(host, port)
        raw.soTimeout = 0
        socket = tlsWrap(raw, host, port)
        Thread(::readLoop, "cast-reader").apply { isDaemon = true }.start()
        Thread(::heartbeatLoop, "cast-heartbeat").apply { isDaemon = true }.start()
        send(CastNs.CONNECTION, RECEIVER, connectMessage())
        log("Conectado por TLS a $host:$port")
    }

    /** CONNECT con los datos de emisor que mandan los emisores oficiales (algunas TV lo exigen). */
    private fun connectMessage() = JSONObject()
        .put("type", "CONNECT")
        .put("connType", 0)
        .put("origin", JSONObject())
        .put("userAgent", "CastBrowser")
        .put("senderInfo", JSONObject()
            .put("sdkType", 2)
            .put("version", "15.605.1.3")
            .put("browserVersion", "44.0.2403.30")
            .put("platform", 4)
            .put("systemVersion", "Android")
            .put("connectionType", 1))

    /** Lanza el Default Media Receiver y carga el vídeo. Bloquea hasta que empieza o falla. */
    override fun load(
        url: String,
        contentType: String,
        title: String,
        startTime: Double,
        live: Boolean,
        hlsFmp4: Boolean,
        headers: Map<String, String>,
    ) {
        playing = false
        launchReceiver()
        val media = JSONObject()
            .put("contentId", url)
            .put("contentUrl", url)
            .put("contentType", contentType)
            .put("streamType", if (live) "LIVE" else "BUFFERED")
            .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
        if (hlsFmp4) {
            media.put("hlsSegmentFormat", "fmp4").put("hlsVideoSegmentFormat", "fmp4")
        }
        val req = JSONObject()
            .put("type", "LOAD")
            .put("media", media)
            .put("autoplay", true)
            .put("currentTime", if (live) 0.0 else startTime)
            .put("sessionId", sessionId)
        log("LOAD → $contentType $url")
        val id = requestId.getAndIncrement()
        mediaEvents.clear()
        send(CastNs.MEDIA, transportId!!, req.put("requestId", id))

        // La primera respuesta suele ser un estado intermedio ("LOADING"): esperamos
        // hasta que el vídeo arranque de verdad o el receptor diga que ha fallado.
        val sentAt = System.currentTimeMillis()
        val deadline = sentAt + loadTimeoutMs
        var lastState = ""
        var lastWaitLog = -1L
        while (true) {
            val now = System.currentTimeMillis()
            val left = deadline - now
            if (left <= 0) {
                // Sólo es éxito si la tele ha avanzado de verdad; "cargando" sin progreso es un fallo.
                if (lastState == "BUFFERING" && lastStatus.currentTime > 0) { playing = true; return }
                throw CastException("El Chromecast no terminó de cargar el vídeo en ${loadTimeoutMs / 1000} s")
            }
            abortCheck?.invoke(now - sentAt)?.let { throw CastException(it) }
            val ev = mediaEvents.poll(minOf(left, 1000L), TimeUnit.MILLISECONDS)
            if (ev == null) {
                if (lastWaitLog < 0 || now - lastWaitLog >= 10_000) {
                    lastWaitLog = now
                    log("Esperando a que la tele cargue el vídeo (${(now - sentAt) / 1000} s)…")
                }
                continue
            }
            when (ev.optString("type")) {
                "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "ERROR" -> {
                    val rid = ev.optInt("requestId", 0)
                    if (rid == id || rid == 0) throw CastException(describeLoadError(ev))
                }
                "MEDIA_STATUS" -> {
                    val st = ev.optJSONArray("status")?.optJSONObject(0) ?: continue
                    lastState = st.optString("playerState")
                    when {
                        lastState == "PLAYING" || lastState == "PAUSED" -> { playing = true; return }
                        lastState == "IDLE" && st.optString("idleReason") == "ERROR" ->
                            throw CastException("El Chromecast no pudo reproducir el vídeo (error al cargarlo)")
                    }
                }
            }
        }
    }

    private fun describeLoadError(resp: JSONObject): String {
        val type = resp.optString("type")
        val code = resp.optInt("detailedErrorCode", 0)
        val reason = resp.optString("reason")
        val hint = when (code) {
            0 -> ""
            104 -> "formato no soportado"
            in 100..103 -> "el reproductor no puede abrir este vídeo"
            in 300..399 -> "el Chromecast no puede descargar el vídeo (red, cookies o CORS)"
            in 400..499 -> "la lista de reproducción (manifiesto) no es válida o no se pudo leer"
            in 200..299 -> "vídeo protegido con DRM"
            else -> ""
        }
        return buildString {
            append("El Chromecast rechazó el vídeo ($type")
            if (code != 0) append(", código $code")
            if (reason.isNotEmpty()) append(", $reason")
            append(")")
            if (hint.isNotEmpty()) append(": ").append(hint)
            else if (code == 0) append(": la TV no consigue abrir la dirección del vídeo")
        }
    }

    private fun launchReceiver() {
        receiverStatus.clear()
        val launchId = requestId.getAndIncrement()
        send(CastNs.RECEIVER, RECEIVER, JSONObject()
            .put("type", "LAUNCH").put("appId", DEFAULT_MEDIA_RECEIVER).put("requestId", launchId))
        log("LAUNCH $DEFAULT_MEDIA_RECEIVER")
        // Algunas TV (Android TV / Google TV) tardan bastante en abrir el reproductor
        // y no siempre avisan solas: preguntamos el estado cada 2 s.
        val deadline = System.currentTimeMillis() + 45_000
        var nextPoll = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            val wait = minOf(deadline, nextPoll) - System.currentTimeMillis()
            val st = receiverStatus.poll(wait.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            if (st == null) {
                if (System.currentTimeMillis() >= nextPoll) {
                    nextPoll = System.currentTimeMillis() + 2000
                    send(CastNs.RECEIVER, RECEIVER, JSONObject()
                        .put("type", "GET_STATUS").put("requestId", requestId.getAndIncrement()))
                }
                continue
            }
            if (st.optString("type") == "LAUNCH_ERROR") {
                throw CastException("La TV no pudo abrir el reproductor de Chromecast (${st.optString("reason")})")
            }
            val apps = st.optJSONObject("status")?.optJSONArray("applications") ?: continue
            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                if (app.optString("appId") == DEFAULT_MEDIA_RECEIVER && app.has("transportId")) {
                    transportId = app.getString("transportId")
                    sessionId = app.optString("sessionId")
                    send(CastNs.CONNECTION, transportId!!, connectMessage())
                    log("Reproductor abierto (transportId=$transportId)")
                    return
                }
            }
        }
        throw CastException("La TV no llegó a abrir el reproductor de Chromecast en 45 s")
    }

    override fun play() = mediaCommand("PLAY")
    override fun pause() = mediaCommand("PAUSE")
    override fun seek(seconds: Double) = mediaCommand("SEEK", JSONObject().put("currentTime", maxOf(0.0, seconds)))

    override fun setVolume(level: Double) {
        send(CastNs.RECEIVER, RECEIVER, JSONObject()
            .put("type", "SET_VOLUME")
            .put("requestId", requestId.getAndIncrement())
            .put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0))))
    }

    /** Cierra la app del Chromecast y la conexión. */
    override fun stop() {
        try {
            sessionId?.let {
                send(CastNs.RECEIVER, RECEIVER, JSONObject()
                    .put("type", "STOP").put("sessionId", it).put("requestId", requestId.getAndIncrement()))
            }
        } catch (_: Exception) {}
        close(null)
    }

    private fun mediaCommand(type: String, extra: JSONObject? = null) {
        val tid = transportId ?: return
        if (mediaSessionId < 0) return
        val msg = extra ?: JSONObject()
        msg.put("type", type).put("mediaSessionId", mediaSessionId).put("requestId", requestId.getAndIncrement())
        Thread {
            try { send(CastNs.MEDIA, tid, msg) } catch (_: Exception) {}
        }.start()
    }

    private fun request(ns: String, dest: String, msg: JSONObject, timeoutSec: Long): JSONObject {
        val id = requestId.getAndIncrement()
        val q = LinkedBlockingQueue<JSONObject>()
        waiters[id] = q
        try {
            send(ns, dest, msg.put("requestId", id))
            return q.poll(timeoutSec, TimeUnit.SECONDS) ?: throw CastException("Tiempo de espera agotado")
        } finally {
            waiters.remove(id)
        }
    }

    @Synchronized
    private fun send(ns: String, dest: String, payload: JSONObject) {
        val s = socket ?: throw CastException("No conectado")
        CastMessage.writeFramed(s.outputStream, CastMessage(SENDER, dest, ns, payload.toString()))
    }

    private fun readLoop() {
        var reason: String? = null
        try {
            val input = DataInputStream(socket!!.inputStream)
            while (!closed) {
                val msg = CastMessage.readFramed(input)
                val json = try { JSONObject(msg.payloadUtf8) } catch (_: Exception) { continue }
                if (msg.namespace != CastNs.HEARTBEAT) summarize(msg.namespace, json)?.let(log)
                when (msg.namespace) {
                    CastNs.HEARTBEAT -> if (json.optString("type") == "PING") {
                        send(CastNs.HEARTBEAT, msg.sourceId, JSONObject().put("type", "PONG"))
                    }
                    CastNs.CONNECTION -> if (json.optString("type") == "CLOSE" && msg.sourceId == transportId) {
                        reason = "El Chromecast cerró la reproducción"
                        break
                    }
                    CastNs.RECEIVER -> {
                        receiverStatus.offer(json)
                        handleReceiverStatus(json)
                    }
                    CastNs.MEDIA -> {
                        if (json.optString("type") == "MEDIA_STATUS") handleMediaStatus(json)
                        mediaEvents.offer(json)
                    }
                }
                val rid = json.optInt("requestId", 0)
                if (rid != 0) waiters[rid]?.offer(json)
            }
        } catch (e: Exception) {
            if (!closed) reason = "Conexión con el Chromecast perdida"
            log("Conexión cerrada: ${e.message}")
        }
        close(reason)
    }

    private var lastSummary = ""

    /** Resume los mensajes del receptor para que el registro sea corto y legible. */
    private fun summarize(ns: String, json: JSONObject): String? {
        val type = json.optString("type")
        val line = when {
            type == "RECEIVER_STATUS" -> {
                val apps = json.optJSONObject("status")?.optJSONArray("applications")
                val names = if (apps == null || apps.length() == 0) "ninguna" else
                    (0 until apps.length()).joinToString { apps.getJSONObject(it).optString("displayName") }
                "← TV: app abierta = $names"
            }
            type == "MEDIA_STATUS" -> {
                val st = json.optJSONArray("status")?.optJSONObject(0) ?: return null
                val state = st.optString("playerState")
                val idle = st.optString("idleReason").let { if (it.isEmpty()) "" else " ($it)" }
                val ext = st.optJSONObject("extendedStatus")?.optString("playerState")?.let { " / $it" } ?: ""
                val vi = st.optJSONObject("videoInfo")?.let { " · reproduciendo ${it.optInt("width")}x${it.optInt("height")}" } ?: ""
                "← TV: $state$ext$idle$vi"
            }
            ns == CastNs.MEDIA || ns == CastNs.RECEIVER -> "← TV: ${json.toString().take(300)}"
            else -> return null
        }
        // No repetimos el mismo estado una y otra vez.
        if (line == lastSummary) return null
        lastSummary = line
        return line
    }

    private fun handleReceiverStatus(json: JSONObject) {
        val status = json.optJSONObject("status") ?: return
        status.optJSONObject("volume")?.let { v ->
            lastStatus = lastStatus.copy(volume = v.optDouble("level", lastStatus.volume), muted = v.optBoolean("muted"))
            onStatus(lastStatus)
        }
        val apps = status.optJSONArray("applications")
        val sid = sessionId
        if (sid != null && playing) {
            var stillRunning = false
            if (apps != null) for (i in 0 until apps.length()) {
                if (apps.getJSONObject(i).optString("sessionId") == sid) stillRunning = true
            }
            if (!stillRunning) close("Otra app ha tomado el Chromecast")
        }
    }

    private fun handleMediaStatus(json: JSONObject) {
        val arr = json.optJSONArray("status") ?: return
        if (arr.length() == 0) return
        val st = arr.getJSONObject(0)
        if (st.has("mediaSessionId")) mediaSessionId = st.getInt("mediaSessionId")
        val duration = st.optJSONObject("media")?.optDouble("duration", Double.NaN)
        lastStatus = lastStatus.copy(
            playerState = st.optString("playerState", lastStatus.playerState),
            currentTime = st.optDouble("currentTime", lastStatus.currentTime),
            duration = if (duration != null && !duration.isNaN()) duration else lastStatus.duration,
            idleReason = st.optString("idleReason").ifEmpty { null },
        )
        onStatus(lastStatus)
    }

    private fun heartbeatLoop() {
        while (!closed) {
            try {
                Thread.sleep(5000)
                if (closed) break
                send(CastNs.HEARTBEAT, RECEIVER, JSONObject().put("type", "PING"))
                // Pedimos estado para que la barra de progreso avance.
                val tid = transportId
                if (tid != null && mediaSessionId >= 0) {
                    send(CastNs.MEDIA, tid, JSONObject().put("type", "GET_STATUS")
                        .put("requestId", requestId.getAndIncrement()))
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    fun close(reason: String?) {
        if (closed) return
        closed = true
        try { socket?.close() } catch (_: Exception) {}
        onClosed(reason)
    }
}
