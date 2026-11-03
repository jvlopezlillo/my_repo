package com.castbrowser.tv

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Modo "empujar": el móvil abre una conexión hacia la tele (la que sí funciona aunque haya VPN)
// y le va enviando el vídeo; el reproductor de la tele lo lee de aquí, en local.
// Sin código de Android para poder probarlo en el PC.

/**
 * Datos empujados por el móvil. Guarda también lo ya leído (una ventana de [keepBehind] bytes) para que un
 * lector pueda volver a abrirse en otra posición, como hace el reproductor al reconectar, sin perder el flujo.
 * El que escribe espera si hay más de [unreadLimit] bytes sin leer (así el móvil no baja más de lo que la tele consume).
 */
class PushStream(
    private val unreadLimit: Int = 16 * 1024 * 1024,
    private val keepBehind: Int = 8 * 1024 * 1024,
) {
    private val lock = Object()
    private val chunks = ArrayList<ByteArray>()
    private val starts = ArrayList<Long>()
    private var base = 0L      // posición del primer byte que se conserva
    private var end = 0L       // bytes recibidos en total
    private var maxRead = 0L   // hasta dónde ha leído el lector que más ha avanzado
    private var finished = false
    private var aborted = false

    val bytesIn: Long get() = synchronized(lock) { end }

    fun write(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            while (!aborted && end - maxRead > 0 && (end - maxRead) + len > unreadLimit) lock.wait(500)
            if (aborted) throw IOException("el flujo se cerró")
            starts.add(end)
            chunks.add(data.copyOfRange(off, off + len))
            end += len
            trim()
            lock.notifyAll()
        }
    }

    /** No llegarán más datos. */
    fun finish() = synchronized(lock) { finished = true; lock.notifyAll() }

    /** Cierra el flujo para siempre: quien escriba recibe un error y los lectores terminan. */
    fun abort() = synchronized(lock) { aborted = true; chunks.clear(); starts.clear(); lock.notifyAll() }

    val isFinished: Boolean get() = synchronized(lock) { finished }

    private fun trim() {
        val limit = maxRead - keepBehind
        while (chunks.size > 1 && starts[0] + chunks[0].size <= limit) {
            base = starts[0] + chunks[0].size
            starts.removeAt(0)
            chunks.removeAt(0)
        }
    }

    private fun indexAt(pos: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= pos) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun reader(start: Long = 0L) = Reader(start)

    inner class Reader(startAt: Long) {
        var position: Long = startAt
            private set

        /** Lee hasta [len] bytes. -1 = fin del flujo; 0 = no llegó nada en [timeoutMs]. */
        fun read(buf: ByteArray, off: Int, len: Int, timeoutMs: Long): Int {
            synchronized(lock) {
                val endAt = System.currentTimeMillis() + timeoutMs
                while (true) {
                    if (aborted) return -1
                    if (position < base) position = base // lo pedido ya se descartó: seguimos desde lo más antiguo
                    if (position < end) break
                    if (finished) return -1
                    val left = endAt - System.currentTimeMillis()
                    if (left <= 0) return 0
                    lock.wait(left)
                }
                var copied = 0
                var i = indexAt(position)
                while (copied < len && i < chunks.size) {
                    val c = chunks[i]
                    val within = (position - starts[i]).toInt()
                    val n = minOf(len - copied, c.size - within)
                    System.arraycopy(c, within, buf, off + copied, n)
                    copied += n
                    position += n
                    i++
                }
                if (position > maxRead) { maxRead = position; trim() }
                lock.notifyAll()
                return copied
            }
        }
    }
}

object PushStreams {
    private val streams = ConcurrentHashMap<String, PushStream>()

    /** Cuántas conexiones de empuje se han recibido (para el diagnóstico). */
    val pushes = AtomicInteger(0)

    // ---- registro de lo que pasa en la tele, para verlo desde el diagnóstico del móvil
    private val events = java.util.ArrayDeque<String>()
    private val started = System.currentTimeMillis()

    fun event(text: String) {
        synchronized(events) {
            events.addLast(String.format(java.util.Locale.ROOT, "%.1fs %s", (System.currentTimeMillis() - started) / 1000.0, text))
            while (events.size > 14) events.removeFirst()
        }
    }

    fun eventsText(): String = synchronized(events) { events.joinToString(" | ") }

    fun getOrCreate(id: String): PushStream = streams.computeIfAbsent(id) { PushStream() }

    fun find(id: String): PushStream? = streams[id]

    /** Cierra todos los flujos (al parar o al empezar otro vídeo). */
    fun closeAll() {
        if (streams.isNotEmpty()) event("flujos cerrados (${streams.size})")
        streams.values.forEach { it.abort() }
        streams.clear()
    }

    /** PUSH /push/<id>: copia lo que envía el móvil hasta que cierre la conexión. */
    fun servePush(id: String, input: InputStream) {
        pushes.incrementAndGet()
        val s = getOrCreate(id)
        event("el móvil empieza a empujar ($id)")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                s.write(buf, 0, n)
                total += n
            }
            event("el móvil terminó de empujar (${total / 1024} KB)")
        } catch (e: IOException) {
            event("empuje cortado tras ${total / 1024} KB: ${e.message}")
        } finally {
            s.finish()
        }
    }

    /**
     * GET /live/<id>.ts por HTTP (con Range opcional). Un lector que se va NO cierra el flujo:
     * el reproductor puede volver a conectarse y seguir leyendo.
     */
    fun serveLive(
        id: String,
        out: OutputStream,
        start: Long = 0L,
        firstDataTimeoutMs: Long = 30_000,
        idleTimeoutMs: Long = 60_000,
    ) {
        val s = getOrCreate(id)
        val r = s.reader(start)
        event("lectura HTTP abierta en $start")
        try {
            val head = if (start > 0)
                "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-*/*\r\n"
            else "HTTP/1.1 200 OK\r\n"
            out.write((head + "Content-Type: video/mp2t\r\nCache-Control: no-cache\r\n" +
                "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val buf = ByteArray(64 * 1024)
            var waited = 0L
            while (true) {
                val n = r.read(buf, 0, buf.size, 500)
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
        } catch (e: IOException) {
            event("lectura HTTP cerrada por el reproductor en ${r.position}")
        }
    }
}
