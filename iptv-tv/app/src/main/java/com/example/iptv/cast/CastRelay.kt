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

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Status.OK, "text/plain", ""))
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return cors(newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain", "Método no permitido"))
        }
        val target = decode(session.uri)
            ?: return cors(newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Prohibido"))
        return try {
            proxy(target)
        } catch (e: Exception) {
            cors(newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "Origen no disponible: ${e.message}"))
        }
    }

    private fun proxy(target: String): Response {
        val req = Request.Builder().url(target).header("User-Agent", UA).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            val status = Status.values().firstOrNull { it.requestStatus == code } ?: Status.SERVICE_UNAVAILABLE
            return cors(newFixedLengthResponse(status, "text/plain", "Origen respondió $code"))
        }
        val body = resp.body!!
        val isPlaylist = runCatching {
            val peek = body.source().peek()
            peek.request(7) && peek.readUtf8(7) == "#EXTM3U"
        }.getOrDefault(false)

        if (isPlaylist) {
            val text = body.string()
            val out = newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", rewrite(text, resp.request.url))
            out.addHeader("Cache-Control", "no-cache")
            return cors(out)
        }

        val type = body.contentType()?.toString() ?: guessType(target)
        val stream = object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try { super.close() } finally { resp.close() }
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
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        r.addHeader("Access-Control-Expose-Headers", "*")
        return r
    }

    private companion object {
        val URI_ATTR = Regex("""URI="([^"]*)"""")
    }
}
