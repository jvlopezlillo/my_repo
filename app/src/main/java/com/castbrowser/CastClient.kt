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
) {
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
    @Volatile private var closed = false

    @Volatile var transportId: String? = null; private set
    @Volatile var sessionId: String? = null; private set
    @Volatile var mediaSessionId: Int = -1; private set
    @Volatile var lastStatus = CastStatus(); private set

    val isConnected: Boolean get() = !closed && socket?.isConnected == true

    fun connect() {
        val raw = rawSocket(host, port)
        raw.soTimeout = 0
        socket = tlsWrap(raw, host, port)
        Thread(::readLoop, "cast-reader").apply { isDaemon = true }.start()
        Thread(::heartbeatLoop, "cast-heartbeat").apply { isDaemon = true }.start()
        send(CastNs.CONNECTION, RECEIVER, JSONObject().put("type", "CONNECT"))
    }

    /** Lanza el Default Media Receiver y carga el vídeo. Bloquea hasta que empieza o falla. */
    fun load(
        url: String,
        contentType: String,
        title: String,
        startTime: Double,
        live: Boolean,
        hlsFmp4: Boolean,
    ) {
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
        val resp = request(CastNs.MEDIA, transportId!!, req, 30)
        when (resp.optString("type")) {
            "MEDIA_STATUS" -> handleMediaStatus(resp)
            else -> throw CastException(
                "El Chromecast rechazó el vídeo (${resp.optString("type")} ${resp.optString("reason")})".trim()
            )
        }
    }

    private fun launchReceiver() {
        receiverStatus.clear()
        val launchId = requestId.getAndIncrement()
        send(CastNs.RECEIVER, RECEIVER, JSONObject()
            .put("type", "LAUNCH").put("appId", DEFAULT_MEDIA_RECEIVER).put("requestId", launchId))
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val st = receiverStatus.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: break
            if (st.optString("type") == "LAUNCH_ERROR") throw CastException("No se pudo abrir el reproductor en el Chromecast")
            val apps = st.optJSONObject("status")?.optJSONArray("applications") ?: continue
            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                if (app.optString("appId") == DEFAULT_MEDIA_RECEIVER && app.has("transportId")) {
                    transportId = app.getString("transportId")
                    sessionId = app.optString("sessionId")
                    send(CastNs.CONNECTION, transportId!!, JSONObject().put("type", "CONNECT"))
                    return
                }
            }
        }
        throw CastException("El Chromecast no respondió al abrir el reproductor")
    }

    fun play() = mediaCommand("PLAY")
    fun pause() = mediaCommand("PAUSE")
    fun seek(seconds: Double) = mediaCommand("SEEK", JSONObject().put("currentTime", maxOf(0.0, seconds)))

    fun setVolume(level: Double) {
        send(CastNs.RECEIVER, RECEIVER, JSONObject()
            .put("type", "SET_VOLUME")
            .put("requestId", requestId.getAndIncrement())
            .put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0))))
    }

    /** Cierra la app del Chromecast y la conexión. */
    fun stop() {
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
                    }
                }
                val rid = json.optInt("requestId", 0)
                if (rid != 0) waiters[rid]?.offer(json)
            }
        } catch (e: Exception) {
            if (!closed) reason = "Conexión con el Chromecast perdida"
        }
        close(reason)
    }

    private fun handleReceiverStatus(json: JSONObject) {
        val status = json.optJSONObject("status") ?: return
        status.optJSONObject("volume")?.let { v ->
            lastStatus = lastStatus.copy(volume = v.optDouble("level", lastStatus.volume), muted = v.optBoolean("muted"))
            onStatus(lastStatus)
        }
        val apps = status.optJSONArray("applications")
        val sid = sessionId
        if (sid != null && mediaSessionId >= 0) {
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
