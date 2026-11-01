package com.example.iptv.cast

import com.example.iptv.data.UA
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil

/**
 * Convierte un directo MPEG-TS (el que sirven casi todas las listas IPTV) en HLS para el Chromecast, sin
 * recodificar: lee el TS, lo corta en segmentos de ~4 s en fotogramas clave (repitiendo PAT/PMT al inicio de
 * cada uno) y mantiene una lista HLS deslizante con los últimos segmentos en memoria.
 * El códec lo decide el canal: el Chromecast admite H.264/HEVC y AAC/AC3; MPEG-2 o audio MP2 no se verían/oirían.
 */
class TsHlsSession(private val url: String, private val client: OkHttpClient) {
    private class Seg(val seq: Int, val data: ByteArray, val duration: Double)

    @Volatile var closed = false
    @Volatile private var lastAccess = System.currentTimeMillis()
    @Volatile private var call: Call? = null
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val segments = ArrayDeque<Seg>()
    private var seq = 0

    // Estado del análisis del flujo (solo lo toca el hilo lector)
    private var pmtPid = -1
    private var videoPid = -1
    private val allowed = HashSet<Int>()
    private val patPkts = ArrayList<ByteArray>()
    private val pmtPkts = ArrayList<ByteArray>()
    private var cur: ByteArrayOutputStream? = null
    private var curPts = -1L
    private var curStartMs = 0L
    private var firstVideoMs = 0L

    fun start() {
        Thread { run() }.apply { isDaemon = true; name = "ts-hls" }.start()
    }

    fun stop() {
        closed = true
        call?.cancel()
        lock.withLock { cond.signalAll() }
    }

    /** Lista HLS actual; espera (hasta 25 s) a que haya al menos 2 segmentos. */
    fun playlist(): String? {
        lastAccess = System.currentTimeMillis()
        lock.withLock {
            val deadline = System.nanoTime() + 25_000_000_000L
            while (segments.size < 2 && !closed) {
                val rem = deadline - System.nanoTime()
                if (rem <= 0) return null
                cond.awaitNanos(rem)
            }
            if (segments.isEmpty()) return null
            val window = segments.takeLast(5)
            val target = ceil(window.maxOf { it.duration }).toInt().coerceAtLeast(1)
            return buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(target).append('\n')
                append("#EXT-X-MEDIA-SEQUENCE:").append(window.first().seq).append('\n')
                window.forEach {
                    append(String.format(Locale.US, "#EXTINF:%.3f,\n", it.duration))
                    append("seg-").append(it.seq).append(".ts\n")
                }
            }
        }
    }

    fun segment(n: Int): ByteArray? {
        lastAccess = System.currentTimeMillis()
        return lock.withLock { segments.firstOrNull { it.seq == n }?.data }
    }

    private fun run() {
        DebugLog.log("TS→HLS: abriendo el origen")
        try {
            val c = client.newCall(Request.Builder().url(url).header("User-Agent", UA).get().build())
            call = c
            c.execute().use { r ->
                if (!r.isSuccessful) {
                    DebugLog.log("TS→HLS: el origen respondió ${r.code}")
                    return
                }
                val input = r.body!!.byteStream().buffered(256 * 1024)
                val pkt = ByteArray(188)
                while (!closed) {
                    if (System.currentTimeMillis() - lastAccess > IDLE_MS) {
                        DebugLog.log("TS→HLS: sin peticiones del Chromecast, cerrando el origen")
                        break
                    }
                    if (!readPacket(input, pkt)) break
                    handle(pkt)
                }
            }
        } catch (e: Exception) {
            if (!closed) DebugLog.log("TS→HLS: error ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            closed = true
            lock.withLock { cond.signalAll() }
            DebugLog.log("TS→HLS: sesión cerrada")
        }
    }

    /** Lee un paquete TS de 188 bytes, resincronizando con el byte 0x47 si hace falta. */
    private fun readPacket(inp: InputStream, p: ByteArray): Boolean {
        var b = inp.read()
        while (b != 0x47) {
            if (b < 0) return false
            b = inp.read()
        }
        p[0] = 0x47
        var off = 1
        while (off < 188) {
            val n = inp.read(p, off, 188 - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun handle(p: ByteArray) {
        val pusi = (p[1].toInt() and 0x40) != 0
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val afc = (p[3].toInt() shr 4) and 0x3
        var off = 4
        var rai = false
        if ((afc and 0x2) != 0) {
            val al = p[4].toInt() and 0xFF
            if (al > 0) rai = (p[5].toInt() and 0x40) != 0
            off = 5 + al
        }
        val hasPayload = (afc and 0x1) != 0 && off < 188

        if (pid == 0) {
            if (pusi && hasPayload) parsePat(p, off)
            keep(patPkts, p, pusi)
            return
        }
        if (pid == pmtPid) {
            if (pusi && hasPayload) parsePmt(p, off)
            keep(pmtPkts, p, pusi)
            return
        }
        if (videoPid < 0 || pid !in allowed) return // aún sin PMT, o programas ajenos

        if (pid == videoPid && pusi && hasPayload) {
            val now = System.currentTimeMillis()
            val pts = parsePts(p, off)
            if (cur == null) {
                if (firstVideoMs == 0L) firstVideoMs = now
                // Empieza en un fotograma clave (o, si el flujo no los marca, a los 3 s)
                if ((rai || now - firstVideoMs > 3000) && patPkts.isNotEmpty() && pmtPkts.isNotEmpty()) startSegment(pts, now)
            } else {
                val wall = (now - curStartMs) / 1000.0
                val elapsed = if (pts >= 0 && curPts >= 0) ((pts - curPts) and MASK33) / 90000.0 else wall
                if (elapsed >= TARGET && (rai || elapsed >= TARGET * 2)) {
                    finishSegment(if (elapsed in 0.5..30.0) elapsed else wall)
                    startSegment(pts, now)
                }
            }
        }
        cur?.write(p, 0, 188)
    }

    private fun keep(list: ArrayList<ByteArray>, p: ByteArray, pusi: Boolean) {
        if (pusi) list.clear()
        if (list.size < 4) list.add(p.copyOf())
    }

    private fun startSegment(pts: Long, now: Long) {
        val b = ByteArrayOutputStream(1 shl 20)
        patPkts.forEach { b.write(it, 0, 188) }
        pmtPkts.forEach { b.write(it, 0, 188) }
        cur = b
        curPts = pts
        curStartMs = now
    }

    private fun finishSegment(duration: Double) {
        val data = cur?.toByteArray() ?: return
        val n: Int
        lock.withLock {
            n = seq++
            segments.addLast(Seg(n, data, duration))
            while (segments.size > 6) segments.removeFirst()
            cond.signalAll()
        }
        if (n < 3 || n % 15 == 0) DebugLog.log(String.format(Locale.US, "TS→HLS: segmento %d · %d KB · %.1f s", n, data.size / 1024, duration))
    }

    private fun parsePat(p: ByteArray, off: Int) {
        if (pmtPid >= 0) return
        val t = off + 1 + (p[off].toInt() and 0xFF)
        if (t + 12 > 188 || (p[t].toInt() and 0xFF) != 0x00) return
        val sectionLen = ((p[t + 1].toInt() and 0x0F) shl 8) or (p[t + 2].toInt() and 0xFF)
        var i = t + 8
        val end = minOf(t + 3 + sectionLen - 4, 188)
        while (i + 4 <= end) {
            val program = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val pid = ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = pid
                return
            }
            i += 4
        }
    }

    private fun parsePmt(p: ByteArray, off: Int) {
        if (videoPid >= 0) return
        val t = off + 1 + (p[off].toInt() and 0xFF)
        if (t + 12 > 188 || (p[t].toInt() and 0xFF) != 0x02) return
        val sectionLen = ((p[t + 1].toInt() and 0x0F) shl 8) or (p[t + 2].toInt() and 0xFF)
        val end = t + 3 + sectionLen - 4
        if (end > 188) return // sección partida en varios paquetes: no soportado
        val pcrPid = ((p[t + 8].toInt() and 0x1F) shl 8) or (p[t + 9].toInt() and 0xFF)
        val progInfoLen = ((p[t + 10].toInt() and 0x0F) shl 8) or (p[t + 11].toInt() and 0xFF)
        var i = t + 12 + progInfoLen
        var firstEs = -1
        val found = StringBuilder()
        val es = HashSet<Int>()
        var video = -1
        while (i + 5 <= end) {
            val type = p[i].toInt() and 0xFF
            val pid = ((p[i + 1].toInt() and 0x1F) shl 8) or (p[i + 2].toInt() and 0xFF)
            val esLen = ((p[i + 3].toInt() and 0x0F) shl 8) or (p[i + 4].toInt() and 0xFF)
            es.add(pid)
            if (firstEs < 0) firstEs = pid
            if (video < 0 && type in VIDEO_TYPES) video = pid
            found.append(String.format(Locale.US, " 0x%02X@%d", type, pid))
            i += 5 + esLen
        }
        val cut = if (video >= 0) video else firstEs
        if (cut < 0) return
        allowed.addAll(es)
        allowed.add(pcrPid)
        videoPid = cut
        DebugLog.log("TS→HLS: PMT pid=$pmtPid pcr=$pcrPid tipos:$found (0x1B=H.264, 0x24=HEVC, 0x02=MPEG-2, 0x0F=AAC, 0x81=AC3, 0x03/0x04=MPEG audio)")
    }

    private fun parsePts(p: ByteArray, off: Int): Long {
        if (off + 14 > 188) return -1
        if (p[off].toInt() != 0 || p[off + 1].toInt() != 0 || p[off + 2].toInt() != 1) return -1
        if (((p[off + 7].toInt() shr 6) and 0x3) < 2) return -1
        fun b(i: Int) = (p[off + i].toInt() and 0xFF).toLong()
        return ((b(9) and 0x0E) shl 29) or (b(10) shl 22) or ((b(11) and 0xFE) shl 14) or (b(12) shl 7) or (b(13) shr 1)
    }

    private companion object {
        const val TARGET = 4.0
        const val IDLE_MS = 45_000L
        const val MASK33 = (1L shl 33) - 1
        val VIDEO_TYPES = setOf(0x02, 0x1B, 0x24, 0x10, 0xEA)
    }
}
