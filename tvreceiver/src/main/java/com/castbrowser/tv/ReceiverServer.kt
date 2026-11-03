package com.castbrowser.tv

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo que recibe las órdenes del navegador CastBrowser.
 * Ver el protocolo en TvReceiverRenderer (app del móvil).
 */
class ReceiverServer(
    private val ctx: Context,
    private val name: String,
    private val id: String,
) {
    private val server: ServerSocket = try {
        ServerSocket(PORT)
    } catch (_: Exception) {
        ServerSocket(0)
    }
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = true

    val port: Int get() = server.localPort

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
        }, "receiver-http").apply { isDaemon = true }.start()
    }

    fun close() {
        running = false
        try { server.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream())
                val parts = (readLine(input) ?: return).split(' ')
                if (parts.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                // El móvil empuja el vídeo por esta conexión (sirve aunque la VPN impida que la tele llegue al móvil).
                if (method == "PUSH" && path.startsWith("/push/")) {
                    s.soTimeout = 120_000
                    PushStreams.servePush(path.removePrefix("/push/"), input)
                    return
                }
                // El reproductor de esta misma tele lee el vídeo empujado.
                if ((method == "GET" || method == "HEAD") && path.startsWith("/live/")) {
                    s.soTimeout = 0
                    val from = Regex("bytes=(\\d+)-").find(headers["range"].orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    PushStreams.serveLive(path.removePrefix("/live/").substringBefore('.'), s.getOutputStream(), from)
                    return
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val (code, resp) = route(parts[0].uppercase(), parts[1].substringBefore('?'), String(body, Charsets.UTF_8))
                val bytes = resp.toString().toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
                out.write(("HTTP/1.1 $code OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun route(method: String, path: String, body: String): Pair<Int, JSONObject> = try {
        when {
            path == "/info" -> 200 to JSONObject()
                .put("app", "castbrowser-tv").put("name", name).put("id", id)
                .put("model", android.os.Build.MODEL).put("version", BuildInfo.VERSION)
                .put("push", true)
                .put("dlna", JSONObject()
                    .put("port", DlnaStatus.httpPort)
                    .put("ssdp", DlnaStatus.ssdp)
                    .put("searches", DlnaStatus.searches.get())
                    .put("interfaces", DlnaStatus.interfaces))
            path == "/status" -> 200 to PlayerHub.status(ctx)
            path == "/play" && method == "POST" -> {
                val j = JSONObject(body)
                val h = HashMap<String, String>()
                j.optJSONObject("headers")?.let { o -> o.keys().forEach { k -> h[k] = o.getString(k) } }
                PlayerHub.play(ctx, MediaRequest(
                    url = j.getString("url"),
                    contentType = j.optString("contentType"),
                    title = j.optString("title", "Vídeo"),
                    position = j.optDouble("position", 0.0),
                    live = j.optBoolean("live"),
                    headers = h,
                ))
                200 to JSONObject().put("ok", true)
            }
            path == "/control" && method == "POST" -> {
                val j = JSONObject(body)
                PlayerHub.control(ctx, j.getString("action"), if (j.has("value")) j.getDouble("value") else null)
                200 to JSONObject().put("ok", true)
            }
            else -> 404 to JSONObject().put("error", "not found")
        }
    } catch (e: Exception) {
        400 to JSONObject().put("error", e.message ?: "bad request")
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    companion object {
        const val PORT = 8765
    }
}

object BuildInfo {
    const val VERSION = "1.1"
}
