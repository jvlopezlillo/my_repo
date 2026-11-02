package com.castbrowser

import java.io.IOException
import java.io.OutputStream
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Convierte un stream HLS (lista .m3u8 + trozos MPEG-TS) en un único flujo MPEG-TS continuo.
// No recodifica nada: sólo une los trozos (y descifra AES-128 si hace falta). Sirve para
// teles DLNA que no entienden HLS pero sí reproducen un .ts por HTTP.

class HlsKey(val uri: String, val iv: ByteArray?)
class HlsSegment(val seq: Long, val url: String, val key: HlsKey?)
class HlsMedia(
    val segments: List<HlsSegment>,
    val targetDuration: Double,
    val endList: Boolean,
    /** Motivo por el que no se puede convertir (fMP4, cifrado SAMPLE-AES…), o null. */
    val unsupported: String?,
)

class HlsUnsupported(msg: String) : Exception(msg)

object Hls {
    private const val MAX_BANDWIDTH = 8_000_000L

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(URI(ref.trim().replace(" ", "%20"))).toString()
    } catch (_: Exception) {
        java.net.URL(java.net.URL(base), ref.trim()).toString()
    }

    /** Atributo de una etiqueta: KEY=valor o KEY="valor". */
    private fun attr(line: String, name: String): String? =
        Regex("(?:^|[:,])$name=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.trim('"')

    private fun hexIv(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** La mejor variante que no pase de 8 Mbps (o la más ligera si todas pasan). */
    fun pickVariant(text: String, base: String): String? {
        val lines = text.lines().map { it.trim() }
        val variants = ArrayList<Pair<Long, String>>()
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("(?<![A-Z-])BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            variants.add(bw to resolve(base, uri))
        }
        if (variants.isEmpty()) return null
        return variants.filter { it.first <= MAX_BANDWIDTH }.maxByOrNull { it.first }?.second
            ?: variants.minByOrNull { it.first }?.second
    }

    fun parseMedia(text: String, base: String): HlsMedia {
        var seq = 0L
        var target = 6.0
        var end = false
        var unsupported: String? = null
        var key: HlsKey? = null
        val segs = ArrayList<HlsSegment>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toDoubleOrNull() ?: 6.0
                line.startsWith("#EXT-X-ENDLIST") -> end = true
                line.startsWith("#EXT-X-MAP") -> unsupported = "segmentos fMP4"
                line.startsWith("#EXT-X-BYTERANGE") -> unsupported = "rangos de bytes"
                line.startsWith("#EXT-X-KEY:") -> {
                    key = when (val method = attr(line, "METHOD")) {
                        null, "NONE" -> null
                        "AES-128" -> HlsKey(resolve(base, attr(line, "URI").orEmpty()), attr(line, "IV")?.let { hexIv(it) })
                        else -> { unsupported = "cifrado $method"; null }
                    }
                }
                line.startsWith("#") -> {}
                else -> segs.add(HlsSegment(seq + segs.size, resolve(base, line), key))
            }
        }
        if (unsupported == null &&
            segs.any { Regex("\\.(m4s|mp4|cmfv|cmfa)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(it.url) }
        ) unsupported = "segmentos fMP4"
        return HlsMedia(segs, target, end, unsupported)
    }
}

class HlsToTs(
    private val fetch: (String) -> ByteArray,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
    /** Si la lista no avanza durante este tiempo, se da el directo por terminado. */
    private val idleLimitMs: Long = 90_000,
) {
    class Session(val mediaUrl: String, val first: HlsMedia)

    /** Lee la lista y comprueba que se puede convertir; lanza [HlsUnsupported] si no. */
    fun open(url: String): Session {
        var u = url
        var text = fetch(u).toString(Charsets.UTF_8)
        if (!text.contains("#EXTM3U")) throw HlsUnsupported("la dirección no devuelve una lista HLS")
        if (Hls.isMaster(text)) {
            u = Hls.pickVariant(text, u) ?: throw HlsUnsupported("la lista maestra no tiene variantes")
            text = fetch(u).toString(Charsets.UTF_8)
        }
        val media = Hls.parseMedia(text, u)
        media.unsupported?.let { throw HlsUnsupported("el vídeo usa $it") }
        if (media.segments.isEmpty()) throw HlsUnsupported("la lista no tiene trozos de vídeo")
        return Session(u, media)
    }

    /** Escribe los trozos uno detrás de otro hasta que acabe el vídeo o se cierre la salida. */
    fun stream(s: Session, out: OutputStream, closed: () -> Boolean = { false }) {
        var media = s.first
        // En directo se empieza 3 trozos antes del final; en VOD, desde el principio.
        var next = if (media.endList) media.segments.first().seq
        else maxOf(media.segments.first().seq, media.segments.last().seq - 2)
        val keys = HashMap<String, ByteArray>()
        var idleMs = 0L
        var failures = 0
        var sent = 0
        while (!closed()) {
            var wrote = false
            for (seg in media.segments) {
                if (seg.seq < next) continue
                val data = try {
                    download(seg, keys)
                } catch (e: IOException) {
                    if (++failures > 5) throw e
                    log("HLS→TS: fallo al bajar el trozo ${seg.seq}: ${e.message} (reintento)")
                    break
                }
                failures = 0
                out.write(data)
                out.flush()
                next = seg.seq + 1
                wrote = true
                if (++sent <= 3) log("HLS→TS: trozo ${seg.seq} enviado (${data.size / 1024} KB)")
            }
            if (media.endList && next > media.segments.last().seq) return
            if (wrote) idleMs = 0 else {
                val wait = maxOf(1000L, (media.targetDuration * 500).toLong())
                idleMs += wait
                if (idleMs > idleLimitMs) return
                sleep(wait)
            }
            media = try {
                Hls.parseMedia(fetch(s.mediaUrl).toString(Charsets.UTF_8), s.mediaUrl).also { m ->
                    // Si la numeración vuelve atrás (el directo se reinició), empezamos de nuevo.
                    if (m.segments.isNotEmpty() && m.segments.last().seq < next - 1) next = m.segments.first().seq
                }
            } catch (e: IOException) {
                if (++failures > 5) throw e
                media
            }
        }
    }

    private fun download(seg: HlsSegment, keys: MutableMap<String, ByteArray>): ByteArray {
        val raw = fetch(seg.url)
        val k = seg.key ?: return raw
        val keyBytes = keys.getOrPut(k.uri) { fetch(k.uri) }
        val iv = k.iv ?: ByteArray(16).also { b ->
            var v = seg.seq
            for (i in 15 downTo 8) { b[i] = (v and 0xFF).toByte(); v = v ushr 8 }
        }
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                doFinal(raw)
            }
        } catch (e: Exception) {
            throw IOException("no se pudo descifrar el trozo ${seg.seq}: ${e.message}")
        }
    }
}
