package com.castbrowser

import java.io.IOException
import java.net.Socket

/**
 * Empuja un HLS convertido a MPEG-TS hacia la app de la tele por una conexión que abre el móvil.
 *
 * Con una VPN puesta, la tele a veces no consigue conectar con el móvil (la VPN lo impide),
 * pero el móvil sí puede conectar con la tele. Así el móvil baja el vídeo (por la VPN, con sus
 * cookies) y se lo va enviando; la tele lo reproduce desde su propio puerto local.
 *
 * Protocolo con la app de la tele: se abre una conexión TCP al puerto 8765 y se envía
 *   PUSH /push/<id> HTTP/1.1 ⏎ cabeceras ⏎⏎
 * seguido de los bytes del vídeo, hasta cerrar la conexión.
 */
class PushStreamer(
    private val connect: () -> Socket,
    val id: String,
    private val converter: HlsToTs,
    private val session: HlsToTs.Session,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var stopped = false
    private var thread: Thread? = null

    @Volatile var bytesSent = 0L
        private set

    val running: Boolean get() = thread?.isAlive == true

    fun start() {
        val s = connect()
        socket = s
        s.tcpNoDelay = true
        s.soTimeout = 0
        val out = s.getOutputStream()
        out.write("PUSH /push/$id HTTP/1.1\r\nHost: tv\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
        log("Push: conexión abierta con la tele (id $id)")

        val counting = object : java.io.OutputStream() {
            override fun write(b: Int) { out.write(b); bytesSent++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); bytesSent += len }
            override fun flush() = out.flush()
        }
        thread = Thread({
            try {
                converter.stream(session, counting) { stopped }
                if (!stopped) log("Push: el vídeo ha terminado (${bytesSent / 1024} KB enviados)")
            } catch (e: IOException) {
                if (!stopped) log("Push: conexión con la tele cortada (${e.message}) tras ${bytesSent / 1024} KB")
            } catch (e: Exception) {
                log("Push: error ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { s.close() } catch (_: Exception) {}
            }
        }, "push-stream").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        try { socket?.close() } catch (_: Exception) {}
    }
}
