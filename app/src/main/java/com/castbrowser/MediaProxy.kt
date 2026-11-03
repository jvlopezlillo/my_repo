package com.castbrowser

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asigna a cada origen remoto (esquema://host:puerto) un prefijo corto "/oN"
 * y reescribe manifiestos HLS / DASH para que todo pase por el proxy.
 */
class ProxyRewriter(private val baseUrl: () -> String) {
    data class Origin(val origin: String, val headers: Map<String, String>)

    private val byOrigin = ConcurrentHashMap<String, String>()
    private val byId = ConcurrentHashMap<String, Origin>()
    private val counter = AtomicInteger(0)

    /** Cabeceras (Referer, User-Agent…) que se envían a cada origen. */
    @Volatile
    var defaultHeaders: Map<String, String> = emptyMap()

    fun originFor(id: String): Origin? = byId[id]

    fun proxify(absoluteUrl: String, headers: Map<String, String>? = null): String {
        val u = URI(absoluteUrl)
        val scheme = u.scheme?.lowercase() ?: return absoluteUrl
        if (scheme != "http" && scheme != "https") return absoluteUrl
        val origin = "$scheme://${u.rawAuthority}"
        val id = byOrigin.computeIfAbsent(origin) { "o" + counter.incrementAndGet() }
        val prev = byId[id]
        val h = headers ?: prev?.headers ?: defaultHeaders
        byId[id] = Origin(origin, h)
        val path = (u.rawPath ?: "").ifEmpty { "/" }
        val query = u.rawQuery?.let { "?$it" } ?: ""
        return baseUrl() + "/" + id + path + query
    }

    /** Reescribe una lista HLS: cada URI (línea o atributo URI="…") pasa por el proxy. */
    fun rewriteM3u8(body: String, base: String): String {
        if (Regex("#EXT-X-(SESSION-)?KEY:METHOD=SAMPLE-AES").containsMatchIn(body)) sawDrm = true
        val baseUri = URI(base)
        val uriAttr = Regex("URI=\"([^\"]*)\"")
        return body.lineSequence().joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttr.replace(line) { m ->
                    val v = m.groupValues[1]
                    if (v.startsWith("data:") || v.startsWith("skd:")) m.value
                    else "URI=\"" + proxify(resolve(baseUri, v)) + "\""
                }
                else -> proxify(resolve(baseUri, line.trim()))
            }
        }
    }

    /**
     * Reescribe un MPD de DASH: las URL absolutas pasan por el proxy; si hubo
     * redirección y no hay BaseURL, se añade una que apunte al directorio final.
     */
    /** true si algún manifiesto pedido declaraba protección DRM. */
    @Volatile var sawDrm = false

    fun rewriteMpd(body: String, finalUrl: String, requestedUrl: String): String {
        if (body.contains("<ContentProtection", ignoreCase = true)) sawDrm = true
        var out = Regex("https?://[^\\s\"'<>]+").replace(body) { m ->
            proxify(m.value.replace("&amp;", "&")).replace("&", "&amp;")
        }
        val finalDir = finalUrl.substringBefore('?').substringBeforeLast('/') + "/"
        val requestedDir = requestedUrl.substringBefore('?').substringBeforeLast('/') + "/"
        if (finalDir != requestedDir && !out.contains("<BaseURL", ignoreCase = true)) {
            val tagEnd = Regex("<MPD[^>]*>").find(out)
            if (tagEnd != null) {
                val insert = "<BaseURL>" + proxify(finalDir).replace("&", "&amp;") + "</BaseURL>"
                out = out.substring(0, tagEnd.range.last + 1) + insert + out.substring(tagEnd.range.last + 1)
            }
        }
        return out
    }

    private fun resolve(base: URI, ref: String): String = try {
        base.resolve(URI(ref.replace(" ", "%20"))).toString()
    } catch (_: Exception) {
        URL(base.toURL(), ref).toString()
    }
}

/**
 * Pequeño servidor HTTP que escucha en la IP Wi-Fi del móvil. El Chromecast le
 * pide el vídeo y el móvil lo descarga (por la VPN si está activa) con las
 * cookies y cabeceras del navegador. También añade las cabeceras CORS que el
 * receptor de Chromecast necesita para HLS/DASH.
 */
class MediaProxy(
    /** IP del móvil que ve la tele (la de la Wi-Fi o la del punto de acceso). */
    @Volatile var host: String,
    private val cookieProvider: (String) -> String? = { null },
) {
    // Escucha en todas las redes locales (Wi-Fi y punto de acceso); sólo atiende a aparatos locales.
    private val server = ServerSocket(0, 50)
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = true

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://$host:${server.localPort}"
    val rewriter = ProxyRewriter { baseUrl }

    /** Último error HTTP (4xx/5xx) que devolvió la web del vídeo. */
    @Volatile var lastUpstreamError: Int? = null

    /** Conexiones recibidas desde que arrancó el proxy (para saber si la tele llega al móvil). */
    val requests = AtomicInteger(0)

    /** Peticiones correctas ya registradas (sólo apuntamos las primeras). */
    @Volatile var okLogged = 0

    private class TsStream(val url: String, val headers: Map<String, String>, val mime: String)

    private val tsStreams = ConcurrentHashMap<String, TsStream>()
    private val tsCounter = AtomicInteger(0)

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        cookieProvider(url)?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Cookie", it) }
        try {
            val code = c.responseCode
            if (code >= 400) { lastUpstreamError = code; throw java.io.IOException("HTTP $code") }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Comprueba que la lista HLS se puede convertir a MPEG-TS; lanza [HlsUnsupported] o IOException si no. */
    fun canRelay(playlistUrl: String, headers: Map<String, String>) {
        HlsToTs({ fetchBytes(it, headers) }, log = log).open(playlistUrl)
    }

    /** Dirección .ts continua (en el móvil) que sirve el HLS convertido a MPEG-TS. */
    fun relayUrl(playlistUrl: String, headers: Map<String, String>, mime: String): String {
        val id = "t" + tsCounter.incrementAndGet()
        tsStreams[id] = TsStream(playlistUrl, headers, mime)
        return "$baseUrl/ts/$id.ts"
    }

    private fun serveTs(method: String, path: String, socket: Socket, out: OutputStream) {
        val id = path.removePrefix("/ts/").substringBefore('?').substringBefore('.')
        val st = tsStreams[id]
        if (st == null) {
            writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))
            return
        }
        log("Proxy: ← $method flujo MPEG-TS (HLS convertido)")
        val conv = HlsToTs({ fetchBytes(it, st.headers) }, log = log)
        val session = try {
            conv.open(st.url)
        } catch (e: Exception) {
            log("Proxy: no se pudo preparar el flujo TS: ${e.message}")
            writeHead(out, 502, "Bad Gateway", mapOf("Content-Length" to "0"))
            return
        }
        // Sin Content-Length: el cuerpo dura hasta que se cierra la conexión (como un directo).
        writeHead(out, 200, "OK", linkedMapOf(
            "Content-Type" to st.mime,
            "Accept-Ranges" to "none",
            "Cache-Control" to "no-cache",
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
        ))
        out.flush()
        if (method == "HEAD") return
        socket.soTimeout = 0
        try {
            conv.stream(session, out) { socket.isClosed }
            log("Proxy: flujo TS terminado")
        } catch (e: java.io.IOException) {
            log("Proxy: flujo TS cortado (${e.message})")
        }
    }

    /** Registro de peticiones (para el diagnóstico). */
    @Volatile var log: (String) -> Unit = {}

    init {
        Thread({
            while (running) {
                try {
                    val s = server.accept()
                    pool.execute { handle(s) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "media-proxy").apply { isDaemon = true }.start()
    }

    fun close() {
        running = false
        try { server.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        requests.incrementAndGet()
        socket.use { s ->
            val remote = s.inetAddress
            if (!(remote.isSiteLocalAddress || remote.isLoopbackAddress || remote.isLinkLocalAddress)) {
                log("Proxy: rechazada conexión desde ${remote.hostAddress} (no es de la red local)")
                return
            }
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val out = s.getOutputStream()

                if (method == "OPTIONS") {
                    writeHead(out, 204, "No Content", mapOf("Content-Length" to "0"))
                    return
                }
                val path = parts[1]
                if (path.startsWith("/ts/")) {
                    serveTs(method, path, s, out)
                    return
                }
                val slash = path.indexOf('/', 1)
                val id = if (slash > 0) path.substring(1, slash) else path.substring(1)
                val origin = rewriter.originFor(id)
                if (origin == null || slash < 0) {
                    writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))
                    return
                }
                val target = origin.origin + path.substring(slash)
                forward(method, target, headers, origin.headers, out)
            } catch (e: Exception) {
                log("Proxy: error ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun forward(
        method: String,
        target: String,
        reqHeaders: Map<String, String>,
        originHeaders: Map<String, String>,
        out: OutputStream,
    ) {
        val conn = URL(target).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        // Muchos servidores no soportan HEAD: pedimos con GET y para HEAD no leemos el cuerpo.
        conn.requestMethod = "GET"
        if (okLogged < 8) {
            log("Proxy: ← $method ${target.substringBefore('?').let { t -> if (t.length > 90) "…" + t.takeLast(89) else t }}" +
                (reqHeaders["range"]?.let { " [$it]" } ?: ""))
        }
        val startedAt = System.currentTimeMillis()
        originHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        reqHeaders["range"]?.let { conn.setRequestProperty("Range", it) }
        cookieProvider(target)?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }

        val code = try { conn.responseCode } catch (e: Exception) {
            log("Proxy: no se pudo descargar ${target.take(120)} (${e.message})")
            502
        }
        if (code >= 400) lastUpstreamError = code
        val shortTarget = target.substringBefore('?').let { t -> if (t.length > 90) "…" + t.takeLast(89) else t }
        if (code >= 400 || okLogged < 8) {
            log("Proxy: $method $shortTarget${reqHeaders["range"]?.let { " [$it]" } ?: ""} → $code (${System.currentTimeMillis() - startedAt} ms)")
        }
        okLogged++
        val stream: InputStream? = try {
            if (code >= 400) conn.errorStream else conn.inputStream
        } catch (_: Exception) { null }
        val contentType = conn.contentType ?: ""
        val finalUrl = conn.url.toString()
        val lowerPath = URI(finalUrl).path?.lowercase() ?: ""

        val isM3u8 = contentType.contains("mpegurl", true) || lowerPath.endsWith(".m3u8")
        val isMpd = contentType.contains("dash+xml", true) || lowerPath.endsWith(".mpd")

        if (code in 200..299 && (isM3u8 || isMpd) && stream != null && method != "HEAD") {
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            val rewritten = if (isM3u8 || text.startsWith("#EXTM3U")) {
                rewriter.rewriteM3u8(text, finalUrl)
            } else {
                rewriter.rewriteMpd(text, finalUrl, target)
            }
            val bytes = rewritten.toByteArray(Charsets.UTF_8)
            writeHead(out, 200, "OK", mapOf(
                "Content-Type" to (if (isM3u8) "application/vnd.apple.mpegurl" else "application/dash+xml"),
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to "no-cache",
            ))
            out.write(bytes)
            out.flush()
            return
        }

        val h = LinkedHashMap<String, String>()
        if (contentType.isNotEmpty()) h["Content-Type"] = contentType
        conn.getHeaderField("Content-Length")?.let { h["Content-Length"] = it }
        conn.getHeaderField("Content-Range")?.let { h["Content-Range"] = it }
        h["Accept-Ranges"] = conn.getHeaderField("Accept-Ranges") ?: "bytes"
        // Algunas teles DLNA (Samsung, LG…) piden estas cabeceras para reproducir en streaming.
        if (reqHeaders.containsKey("getcontentfeatures.dlna.org")) {
            h["contentFeatures.dlna.org"] = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        }
        if (reqHeaders.containsKey("transfermode.dlna.org")) h["transferMode.dlna.org"] = "Streaming"
        writeHead(out, code, conn.responseMessage ?: "OK", h)
        if (method != "HEAD" && stream != null) {
            stream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        out.flush()
        conn.disconnect()
    }

    private fun writeHead(out: OutputStream, code: Int, msg: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Content-Type\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16 * 1024) return null
        }
    }
}
