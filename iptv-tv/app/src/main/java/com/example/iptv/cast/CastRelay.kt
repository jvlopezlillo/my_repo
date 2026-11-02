package com.example.iptv.cast

import android.util.Base64
import com.example.iptv.data.UA
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Servidor HTTP local para el Chromecast: el móvil descarga el vídeo (por la VPN/proxy que tenga) y lo
 * sirve en la red local con cabeceras CORS. Las listas HLS (.m3u8) se reescriben para que los segmentos
 * también pasen por aquí.
 *
 * Solo sirve URLs firmadas por este mismo servidor (HMAC con un secreto aleatorio por ejecución): no es
 * un proxy abierto para el resto de dispositivos de la red.
 */
class CastRelay(@Volatile var client: OkHttpClient) : NanoHTTPD(0) {
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val b64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    private val tsSessions = java.util.concurrent.ConcurrentHashMap<String, TsHlsSession>()

    /** Empieza a convertir un directo MPEG-TS a HLS; devuelve el identificador de la sesión. Solo hay una a la vez. */
    fun startTs(target: String): String {
        tsSessions.values.forEach { it.stop() }
        tsSessions.clear()
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        tsSessions[id] = TsHlsSession(target, client).also { it.start() }
        return id
    }

    fun tsUrl(host: String, id: String): String = "http://$host:$listeningPort/t/$id/index.m3u8"

    private fun serveTs(uri: String): Response {
        val parts = uri.removePrefix("/t/").split('/')
        val s = tsSessions[parts[0]] ?: return cors(newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Sesión desconocida"))
        val file = parts.getOrNull(1) ?: ""
        if (file == "index.m3u8") {
            val pl = s.playlist()
                ?: return cors(newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "Aún no hay segmentos"))
            DebugLog.log("TS→HLS lista servida (${pl.lines().count { it.startsWith("seg-") }} segmentos)")
            val out = newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", pl)
            out.addHeader("Cache-Control", "no-cache")
            return cors(out)
        }
        val n = Regex("""seg-(\d+)\.ts""").matchEntire(file)?.groupValues?.get(1)?.toIntOrNull()
        val data = n?.let { s.segment(it) }
            ?: return cors(newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Segmento no disponible"))
        return cors(newFixedLengthResponse(Status.OK, "video/mp2t", java.io.ByteArrayInputStream(data), data.size.toLong()))
    }

    /** Cierra ya las conversiones TS→HLS (libera la conexión con el proveedor, que a menudo limita las simultáneas). */
    fun stopTs() {
        tsSessions.values.forEach { it.stop() }
        tsSessions.clear()
    }

    override fun stop() {
        stopTs()
        super.stop()
    }

    fun urlFor(host: String, target: String): String = "http://$host:$listeningPort${path(target)}"

    private fun sig(s: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(s.toByteArray()).copyOf(12), b64)
    }

    private fun path(target: String): String {
        val enc = Base64.encodeToString(target.toByteArray(), b64)
        return "/r/$enc.${sig(enc)}"
    }

    private fun decode(uri: String): String? {
        if (!uri.startsWith("/r/")) return null
        val body = uri.removePrefix("/r/")
        val enc = body.substringBeforeLast('.')
        val given = body.substringAfterLast('.', "")
        if (!MessageDigest.isEqual(sig(enc).toByteArray(), given.toByteArray())) return null
        return runCatching { String(Base64.decode(enc, b64)) }.getOrNull()
    }

    /** Peticiones recibidas (sirve para saber si el Chromecast llega a contactar con el móvil). */
    val requestCount = java.util.concurrent.atomic.AtomicInteger()
    private val reqHeaders = ThreadLocal<Map<String, String>>()

    override fun serve(session: IHTTPSession): Response {
        requestCount.incrementAndGet()
        val h: Map<String, String> = session.headers ?: emptyMap()
        reqHeaders.set(h)
        val shown = when {
            session.uri.startsWith("/r/") -> "/r/…"
            session.uri.startsWith("/t/") -> "/t/…/" + session.uri.substringAfterLast('/')
            else -> session.uri.take(30)
        }
        DebugLog.log(
            "relay ← ${session.remoteIpAddress} ${session.method} $shown origin=${h["origin"]} " +
                "ua=${h["user-agent"]?.take(40)} acrh=${h["access-control-request-headers"]} " +
                "pna=${h["access-control-request-private-network"]}",
        )
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Status.OK, "text/plain", ""))
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return cors(newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain", "Método no permitido"))
        }
        if (session.uri == "/ping") {
            return cors(newFixedLengthResponse(Status.OK, "text/plain", "IPTV TV: el servidor local del móvil está activo y es alcanzable.\n"))
        }
        if (session.uri.startsWith("/t/")) return serveTs(session.uri)
        val target = decode(session.uri)
        if (target == null) {
            DebugLog.log("relay ✗ ${session.remoteIpAddress} petición no firmada: ${session.uri.take(40)}")
            return cors(newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Prohibido"))
        }
        DebugLog.log("relay: origen ${DebugLog.redact(target)}")
        return try {
            proxy(target)
        } catch (e: Exception) {
            DebugLog.log("relay ✗ ${e.javaClass.simpleName}: ${e.message}")
            cors(newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "Origen no disponible: ${e.message}"))
        }
    }

    /** Se llama cuando el origen sigue rechazando tras los reintentos (p. ej. límite de conexiones). */
    @Volatile var onDenied: ((Int) -> Unit)? = null

    private fun proxy(target: String): Response {
        val req = Request.Builder().url(target).header("User-Agent", UA).get().build()
        var resp = client.newCall(req).execute()
        var tries = 0
        while (!resp.isSuccessful && resp.code in RETRYABLE && tries < 2) {
            DebugLog.log("relay ✗ origen ${resp.code} ${DebugLog.describeError(resp)} · reintento ${tries + 1}/2")
            resp.close()
            Thread.sleep(1000L * (tries + 1))
            resp = client.newCall(req).execute()
            tries++
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            DebugLog.log("relay ✗ el origen respondió $code ${DebugLog.describeError(resp)}")
            resp.close()
            if (code in RETRYABLE) onDenied?.invoke(code)
            val status = Status.values().firstOrNull { it.requestStatus == code } ?: Status.SERVICE_UNAVAILABLE
            return cors(newFixedLengthResponse(status, "text/plain", "Origen respondió $code"))
        }
        val body = resp.body!!
        val isPlaylist = runCatching {
            val src = body.source()
            src.request(512)
            src.buffer.snapshot(minOf(512L, src.buffer.size).toInt()).utf8().contains("#EXTM3U")
        }.getOrDefault(false)
        DebugLog.log("relay → origen ${resp.code} ${body.contentType()} lista=$isPlaylist")

        if (isPlaylist) {
            val text = body.string()
            val rewritten = rewrite(text, resp.request.url)
            DebugLog.log("relay lista reescrita: ${text.lines().size} líneas")
            val out = newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", rewritten)
            out.addHeader("Cache-Control", "no-cache")
            return cors(out)
        }

        val type = body.contentType()?.toString() ?: guessType(target)
        val stream = object : FilterInputStream(body.byteStream()) {
            var sent = 0L
            var done = false
            override fun read(): Int = super.read().also { if (it >= 0) sent++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) sent += it }
            override fun close() {
                if (done) return // NanoHTTPD puede cerrar dos veces
                done = true
                try { super.close() } finally {
                    resp.close()
                    DebugLog.log("relay ✓ enviados ${sent / 1024} KB (${DebugLog.redact(target)})")
                }
            }
        }
        val len = body.contentLength()
        return cors(if (len >= 0) newFixedLengthResponse(Status.OK, type, stream, len) else newChunkedResponse(Status.OK, type, stream))
    }

    /** Sustituye cada URL (líneas sueltas y atributos URI="...") por su versión firmada local. */
    private fun rewrite(text: String, base: HttpUrl): String = buildString {
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> append(line)
                line.startsWith("#") -> append(URI_ATTR.replace(line) { m ->
                    val abs = base.resolve(m.groupValues[1])
                    if (abs == null) m.value else "URI=\"" + path(abs.toString()) + "\""
                })
                else -> append(base.resolve(line.trim())?.let { path(it.toString()) } ?: line)
            }
            append('\n')
        }
    }

    private fun guessType(url: String): String {
        val p = url.substringBefore('?').lowercase()
        return when {
            p.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            p.endsWith(".ts") -> "video/mp2t"
            p.endsWith(".aac") -> "audio/aac"
            p.endsWith(".mp4") || p.endsWith(".m4s") -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    private fun cors(r: Response): Response {
        val h = reqHeaders.get() ?: emptyMap()
        val origin = h["origin"]
        if (origin != null) {
            r.addHeader("Access-Control-Allow-Origin", origin)
            r.addHeader("Access-Control-Allow-Credentials", "true")
            r.addHeader("Vary", "Origin")
        } else {
            r.addHeader("Access-Control-Allow-Origin", "*")
        }
        r.addHeader("Access-Control-Allow-Headers", h["access-control-request-headers"] ?: "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        r.addHeader("Access-Control-Allow-Private-Network", "true") // el receptor (https) accede a una IP privada
        r.addHeader("Access-Control-Max-Age", "600")
        r.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Content-Type")
        return r
    }

    private companion object {
        val URI_ATTR = Regex("""URI="([^"]*)"""")
        val RETRYABLE = setOf(403, 429, 503)
    }
}
