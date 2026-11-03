package com.castbrowser.tv

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Modo "empujar": el móvil abre una conexión hacia la tele (la que sí funciona aunque haya VPN)
// y le va enviando el vídeo; el reproductor de la tele lo lee de aquí, en local.
// Sin código de Android para poder probarlo en el PC.

/** Búfer entre la conexión que EMPUJA el móvil y el reproductor que LEE. */
class PushStream(private val capacity: Int = 32 * 1024 * 1024) {
    private val lock = Object()
    private val chunks = java.util.ArrayDeque<ByteArray>()
    private var queued = 0
    private var finished = false
    private var aborted = false
    private var current: ByteArray? = null
    private var currentPos = 0

    @Volatile var bytesIn = 0L
        private set

    /** Añade datos; espera si el búfer está lleno (así el móvil no baja más rápido de lo que la tele consume). */
    fun write(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            while (!aborted && queued > 0 && queued + len > capacity) lock.wait(500)
            if (aborted) throw IOException("el reproductor de la tele se cerró")
            chunks.add(data.copyOfRange(off, off + len))
            queued += len
            bytesIn += len
            lock.notifyAll()
        }
    }

    /** No llegarán más datos. */
    fun finish() = synchronized(lock) { finished = true; lock.notifyAll() }

    /** El lector se ha ido: descarta lo pendiente y hace fallar a quien escriba. */
    fun abort() = synchronized(lock) { aborted = true; chunks.clear(); current = null; queued = 0; lock.notifyAll() }

    /** Lee hasta buf.size bytes. -1 = fin; 0 = no llegó nada en [timeoutMs]. */
    fun read(buf: ByteArray, timeoutMs: Long): Int {
        synchronized(lock) {
            val end = System.currentTimeMillis() + timeoutMs
            while (chunks.isEmpty() && current == null && !finished && !aborted) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return 0
                lock.wait(left)
            }
            if (aborted) return -1
            if (current == null) {
                current = chunks.poll()
                currentPos = 0
                if (current == null) return if (finished) -1 else 0
            }
            val c = current!!
            val n = minOf(buf.size, c.size - currentPos)
            System.arraycopy(c, currentPos, buf, 0, n)
            currentPos += n
            queued -= n
            if (currentPos >= c.size) current = null
            lock.notifyAll()
            return n
        }
    }
}

object PushStreams {
    private val streams = ConcurrentHashMap<String, PushStream>()

    /** Cuántas conexiones de empuje se han recibido (para el diagnóstico). */
    val pushes = AtomicInteger(0)

    fun getOrCreate(id: String): PushStream = streams.computeIfAbsent(id) { PushStream() }

    /** Cierra todos los flujos (al parar o al empezar otro vídeo). */
    fun closeAll() {
        streams.values.forEach { it.abort() }
        streams.clear()
    }

    /** PUSH /push/<id>: copia lo que envía el móvil hasta que cierre la conexión. */
    fun servePush(id: String, input: InputStream) {
        pushes.incrementAndGet()
        val s = getOrCreate(id)
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                s.write(buf, 0, n)
            }
        } catch (_: IOException) {
        } finally {
            s.finish()
        }
    }

    /** GET /live/<id>.ts: lo que lee el reproductor. Termina cuando el móvil acaba o se cae. */
    fun serveLive(id: String, out: OutputStream, firstDataTimeoutMs: Long = 30_000, idleTimeoutMs: Long = 60_000) {
        val s = getOrCreate(id)
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\n" +
                "Accept-Ranges: none\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val buf = ByteArray(64 * 1024)
            var waited = 0L
            while (true) {
                val n = s.read(buf, 500)
                if (n < 0) break
                if (n == 0) {
                    waited += 500
                    val limit = if (s.bytesIn == 0L) firstDataTimeoutMs else idleTimeoutMs
                    if (waited >= limit) break
                    continue
                }
                waited = 0
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: IOException) {
            // El reproductor cerró: avisamos al que escribe para que pare.
            s.abort()
        } finally {
            streams.remove(id, s)
        }
    }
}
