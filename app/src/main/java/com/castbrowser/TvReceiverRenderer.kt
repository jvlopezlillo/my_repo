package com.castbrowser

import org.json.JSONObject

/**
 * Envía vídeos a nuestra app receptora para Android TV ("CastBrowser TV").
 *
 * Protocolo (HTTP + JSON, puerto 8765 por defecto):
 *  - GET  /info     → {"name","version"}
 *  - POST /play     ← {"url","contentType","title","position","live","headers":{…}}
 *  - POST /control  ← {"action":"play|pause|seek|stop|volume","value":n}
 *  - GET  /status   → {"state","position","duration","volume","error"}
 *    state: idle | loading | buffering | playing | paused | ended | error
 */
class TvReceiverRenderer(
    private val host: String,
    private val port: Int,
    private val open: HttpOpener,
    private val onStatus: (CastStatus) -> Unit,
    private val onClosed: (String?) -> Unit,
    private val log: (String) -> Unit = {},
    private val loadTimeoutMs: Long = 60_000,
) : Renderer {
    companion object {
        const val DEFAULT_PORT = 8765
    }

    @Volatile private var abortCheck: ((Long) -> String?)? = null
    override fun setAbortCheck(check: ((Long) -> String?)?) { abortCheck = check }

    @Volatile override var lastStatus = CastStatus(); private set
    @Volatile private var closed = false

    /** La app de la tele admite que el móvil le empuje el vídeo. */
    @Volatile var pushSupported = false; private set

    private val base get() = "http://$host:$port"

    override fun connect() {
        val (code, body) = Http.request(open, "$base/info", timeoutMs = 4000)
        if (code != 200) throw CastException("La app receptora de la TV no responde (HTTP $code)")
        val info = JSONObject(body)
        log("TV: ${info.optString("name")} v${info.optString("version")}")
        info.optJSONObject("dlna")?.let {
            log("TV: DLNA puerto ${it.optInt("port")} · SSDP ${it.optString("ssdp")} · búsquedas recibidas ${it.optInt("searches")} · redes ${it.optString("interfaces")}")
        }
        pushSupported = info.optBoolean("push")
    }

    override fun load(
        url: String,
        contentType: String,
        title: String,
        startTime: Double,
        live: Boolean,
        hlsFmp4: Boolean,
        headers: Map<String, String>,
    ) {
        val req = JSONObject()
            .put("url", url)
            .put("contentType", contentType)
            .put("title", title)
            .put("position", if (live) 0.0 else startTime)
            .put("live", live)
            .put("headers", JSONObject(headers))
        log("TV: play $url")
        val (code, body) = Http.request(open, "$base/play", "POST", req.toString(),
            mapOf("Content-Type" to "application/json"), timeoutMs = 8000)
        if (code != 200) throw CastException("La app receptora rechazó el vídeo (HTTP $code ${body.take(100)})")

        val sentAt = System.currentTimeMillis()
        val deadline = sentAt + loadTimeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(800)
            abortCheck?.invoke(System.currentTimeMillis() - sentAt)?.let { throw CastException(it) }
            val st = fetchStatus() ?: continue
            val state = st.optString("state")
            if (state != last) { log("TV: estado $state"); last = state }
            when (state) {
                "playing", "paused" -> {
                    Thread(::pollLoop, "tv-poll").apply { isDaemon = true }.start()
                    return
                }
                "error" -> throw CastException("La TV no pudo reproducir el vídeo: ${st.optString("error")}")
            }
        }
        // "Cargando" sin avance no es éxito: así se prueba el siguiente camino en vez de esperar para nada.
        if (last == "buffering" && lastStatus.currentTime > 0) {
            Thread(::pollLoop, "tv-poll").apply { isDaemon = true }.start()
            return
        }
        throw CastException("La TV no empezó a reproducir en ${loadTimeoutMs / 1000} s (estado: ${last.ifEmpty { "sin respuesta" }})")
    }

    private fun fetchStatus(): JSONObject? = try {
        val (code, body) = Http.request(open, "$base/status", timeoutMs = 3000)
        if (code == 200) JSONObject(body).also { apply(it) } else null
    } catch (_: Exception) { null }

    @Volatile private var lastVideo = ""

    private fun apply(st: JSONObject) {
        val state = st.optString("state")
        st.optString("video").takeIf { it.isNotEmpty() && it != lastVideo }?.let {
            lastVideo = it
            log("TV: reproduciendo a $it")
        }
        lastStatus = lastStatus.copy(
            playerState = when (state) {
                "playing" -> "PLAYING"
                "paused" -> "PAUSED"
                "buffering", "loading" -> "BUFFERING"
                else -> "IDLE"
            },
            currentTime = st.optDouble("position", 0.0),
            duration = st.optDouble("duration", 0.0),
            volume = st.optDouble("volume", lastStatus.volume),
            idleReason = when (state) { "ended" -> "FINISHED"; "error" -> "ERROR"; else -> null },
        )
        onStatus(lastStatus)
    }

    private fun pollLoop() {
        var failures = 0
        while (!closed) {
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) { break }
            if (closed) break
            if (fetchStatus() == null) {
                if (++failures >= 6) { close("Se perdió la conexión con la TV"); break }
            } else failures = 0
        }
    }

    private fun control(action: String, value: Double? = null) {
        Thread {
            try {
                val body = JSONObject().put("action", action)
                if (value != null) body.put("value", value)
                Http.request(open, "$base/control", "POST", body.toString(), mapOf("Content-Type" to "application/json"))
                fetchStatus()
            } catch (e: Exception) { log("TV: ${e.message}") }
        }.start()
    }

    override fun play() = control("play")
    override fun pause() = control("pause")
    override fun seek(seconds: Double) = control("seek", maxOf(0.0, seconds))
    override fun setVolume(level: Double) {
        lastStatus = lastStatus.copy(volume = level.coerceIn(0.0, 1.0))
        control("volume", level.coerceIn(0.0, 1.0))
    }

    override fun stop() {
        try {
            Http.request(open, "$base/control", "POST", JSONObject().put("action", "stop").toString(),
                mapOf("Content-Type" to "application/json"), timeoutMs = 3000)
        } catch (_: Exception) {}
        close(null)
    }

    private fun close(reason: String?) {
        if (closed) return
        closed = true
        onClosed(reason)
    }
}
