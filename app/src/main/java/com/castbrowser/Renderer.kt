package com.castbrowser

import java.net.HttpURLConnection

/**
 * Algo que puede reproducir un vídeo en la tele: un Chromecast, un aparato
 * DLNA o nuestra app receptora para Android TV.
 */
interface Renderer {
    val lastStatus: CastStatus

    /** Abre la conexión con el aparato. */
    fun connect()

    /** Empieza a reproducir. Bloquea hasta que el vídeo arranca o lanza [CastException]. */
    fun load(
        url: String,
        contentType: String,
        title: String,
        startTime: Double,
        live: Boolean,
        hlsFmp4: Boolean,
        headers: Map<String, String>,
    )

    fun play()
    fun pause()
    fun seek(seconds: Double)
    fun setVolume(level: Double)

    /** Detiene la reproducción y cierra la conexión. */
    fun stop()

    /**
     * Comprobación que se consulta mientras se espera a que el vídeo arranque (con los ms
     * transcurridos desde que se envió); si devuelve un texto, el envío se cancela con ese error.
     */
    fun setAbortCheck(check: ((elapsedMs: Long) -> String?)?) {}
}

/** Abre una conexión HTTP (en Android, atada a la Wi-Fi para saltar la VPN). */
typealias HttpOpener = (String) -> HttpURLConnection

internal object Http {
    fun request(
        open: HttpOpener,
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 5000,
    ): Pair<Int, String> {
        val c = open(url)
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        return code to text
    }
}
