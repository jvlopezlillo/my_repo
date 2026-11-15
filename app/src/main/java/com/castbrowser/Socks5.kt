package com.castbrowser

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

// Proxy SOCKS5 para navegar y para las descargas de vídeo del móvil. Sin código de Android.
//
// El navegador (WebView) sólo sabe usar un proxy HTTP sin contraseña, así que el móvil abre un
// "puente" local (127.0.0.1) que habla proxy HTTP con el WebView y SOCKS5 (con usuario y contraseña)
// con tu proxy. Los nombres de las webs los resuelve el proxy, no el móvil.

data class Socks5Config(val host: String, val port: Int, val user: String = "", val pass: String = "") {
    val hasAuth: Boolean get() = user.isNotEmpty()
    val label: String get() = "$host:$port"
}

class Socks5Exception(message: String) : IOException(message)

object Socks5 {
    private fun replyText(rep: Int) = when (rep) {
        1 -> "fallo general del servidor SOCKS"
        2 -> "el proxy no permite esa conexión (reglas del proxy)"
        3 -> "red inalcanzable desde el proxy"
        4 -> "servidor inalcanzable desde el proxy"
        5 -> "conexión rechazada por el destino"
        6 -> "tiempo agotado (TTL) en el proxy"
        7 -> "el proxy no admite esa orden"
        8 -> "el proxy no admite ese tipo de dirección"
        else -> "error $rep del proxy"
    }

    /** Abre una conexión TCP a [host]:[port] a través del proxy SOCKS5. */
    fun connect(cfg: Socks5Config, host: String, port: Int, timeoutMs: Int = 10_000): Socket {
        val s = Socket()
        try {
            try {
                s.connect(InetSocketAddress(cfg.host, cfg.port), timeoutMs)
            } catch (e: IOException) {
                throw Socks5Exception("no se puede conectar con el proxy ${cfg.label} (${e.message})")
            }
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            handshake(s, cfg, host, port)
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    private fun handshake(s: Socket, cfg: Socks5Config, host: String, port: Int) {
        val out = s.getOutputStream()
        val inp = DataInputStream(s.getInputStream())

        // 1) Saludo: métodos de autenticación que ofrecemos.
        val methods = if (cfg.hasAuth) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00)
        out.write(byteArrayOf(0x05, methods.size.toByte()) + methods)
        out.flush()
        val ver = readByte(inp)
        val method = readByte(inp)
        if (ver != 5) throw Socks5Exception("ese servidor no habla SOCKS5")
        when (method) {
            0x00 -> {}
            0x02 -> {
                if (!cfg.hasAuth) throw Socks5Exception("el proxy pide usuario y contraseña")
                val u = cfg.user.toByteArray(Charsets.UTF_8)
                val p = cfg.pass.toByteArray(Charsets.UTF_8)
                if (u.size > 255 || p.size > 255) throw Socks5Exception("usuario o contraseña demasiado largos")
                out.write(byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                out.flush()
                readByte(inp)
                if (readByte(inp) != 0) throw Socks5Exception("usuario o contraseña incorrectos")
            }
            0xFF -> throw Socks5Exception(
                if (cfg.hasAuth) "el proxy no acepta usuario y contraseña" else "el proxy pide usuario y contraseña"
            )
            else -> throw Socks5Exception("el proxy pide una autenticación no soportada ($method)")
        }

        // 2) Petición CONNECT. El nombre va tal cual (ATYP 3) para que lo resuelva el proxy.
        val req = ByteArrayOutputStream()
        req.write(byteArrayOf(0x05, 0x01, 0x00))
        val h = host.trim('[', ']')
        when {
            ipv4.matches(h) -> { req.write(0x01); req.write(InetAddress.getByName(h).address) }
            h.contains(':') -> { req.write(0x04); req.write(InetAddress.getByName(h).address) }
            else -> {
                val b = h.toByteArray(Charsets.UTF_8)
                if (b.size > 255) throw Socks5Exception("nombre de servidor demasiado largo")
                req.write(0x03); req.write(b.size); req.write(b)
            }
        }
        req.write(port ushr 8); req.write(port and 0xFF)
        out.write(req.toByteArray())
        out.flush()

        // 3) Respuesta: VER REP RSV ATYP BND.ADDR BND.PORT
        readByte(inp)
        val rep = readByte(inp)
        readByte(inp)
        val atyp = readByte(inp)
        val skip = when (atyp) { 1 -> 4; 4 -> 16; 3 -> readByte(inp); else -> 0 }
        inp.skipBytes(skip + 2)
        if (rep != 0) throw Socks5Exception(replyText(rep))
    }

    private fun readByte(inp: DataInputStream): Int = inp.read().also {
        if (it < 0) throw Socks5Exception("el proxy ha cerrado la conexión")
    }

    /** Comprueba el proxy y devuelve la IP con la que sales a internet. */
    fun checkExitIp(cfg: Socks5Config, host: String = "api.ipify.org", port: Int = 80, timeoutMs: Int = 10_000): String {
        connect(cfg, host, port, timeoutMs).use { s ->
            s.soTimeout = timeoutMs
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: CastBrowser\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            if (!text.startsWith("HTTP/1.")) throw Socks5Exception("respuesta inesperada del servidor de comprobación")
            return text.substringAfter("\r\n\r\n", "").trim().lineSequence().lastOrNull()?.trim().orEmpty()
                .ifEmpty { throw Socks5Exception("el servidor de comprobación no devolvió la IP") }
        }
    }
}

/** Conexiones que deben salir por el proxy (descargas de vídeo del móvil). Las de la red local NO pasan por aquí. */
object Upstream {
    @Volatile var proxy: Proxy? = null

    fun open(url: String): HttpURLConnection {
        val p = proxy
        val u = URL(url)
        return (if (p != null) u.openConnection(p) else u.openConnection()) as HttpURLConnection
    }
}

/**
 * Proxy HTTP local (127.0.0.1) que reenvía todo por SOCKS5.
 *  - CONNECT host:puerto (HTTPS) → túnel.
 *  - GET http://… (HTTP normal) → se reenvía la petición y se cierra al terminar.
 */
class LocalProxy(private val cfg: Socks5Config, private val log: (String) -> Unit = {}) {
    private val server = ServerSocket(0, 100, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    val port: Int get() = server.localPort

    fun start() {
        if (running) return
        running = true
        Thread({
            while (running && !server.isClosed) {
                try {
                    val c = server.accept()
                    pool.execute { handle(c) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "local-proxy").apply { isDaemon = true }.start()
    }

    fun close() {
        running = false
        try { server.close() } catch (_: Exception) {}
        pool.shutdownNow()
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

    private fun fail(out: OutputStream, code: Int, text: String, why: String) {
        val body = "$why\n".toByteArray(Charsets.UTF_8)
        try {
            out.write(("HTTP/1.1 $code $text\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        } catch (_: Exception) {}
    }

    private fun parseHostPort(t: String, defaultPort: Int): Pair<String, Int> {
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            val host = t.substring(1, end)
            val port = t.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
        val i = t.lastIndexOf(':')
        return if (i > 0 && t.indexOf(':') == i) t.substring(0, i) to (t.substring(i + 1).toIntOrNull() ?: defaultPort) else t to defaultPort
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            val line = readLine(input) ?: return
            val parts = line.split(' ')
            if (parts.size < 2) return
            val headers = ArrayList<Pair<String, String>>()
            while (true) {
                val l = readLine(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers.add(l.substring(0, i).trim() to l.substring(i + 1).trim())
            }
            val method = parts[0].uppercase()

            if (method == "CONNECT") {
                val (host, port) = parseHostPort(parts[1], 443)
                upstream = try {
                    Socks5.connect(cfg, host, port)
                } catch (e: Exception) {
                    log("Proxy SOCKS5: $host:$port → ${e.message}")
                    fail(out, 502, "Bad Gateway", "Proxy SOCKS5: ${e.message}")
                    return
                }
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
            } else {
                // HTTP normal: la petición llega con la dirección completa.
                val uri = try { URI(parts[1]) } catch (_: Exception) { null }
                val host = uri?.host
                if (uri == null || host == null) { fail(out, 400, "Bad Request", "Petición no válida"); return }
                val port = if (uri.port > 0) uri.port else 80
                upstream = try {
                    Socks5.connect(cfg, host, port)
                } catch (e: Exception) {
                    log("Proxy SOCKS5: $host:$port → ${e.message}")
                    fail(out, 502, "Bad Gateway", "Proxy SOCKS5: ${e.message}")
                    return
                }
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val sb = StringBuilder("$method $path HTTP/1.1\r\n")
                val drop = setOf("proxy-connection", "connection", "keep-alive", "proxy-authorization", "te", "upgrade")
                var hasHost = false
                for ((k, v) in headers) {
                    if (k.lowercase() in drop) continue
                    if (k.equals("host", true)) hasHost = true
                    sb.append(k).append(": ").append(v).append("\r\n")
                }
                if (!hasHost) sb.append("Host: ").append(uri.authority).append("\r\n")
                sb.append("Connection: close\r\n\r\n")
                upstream.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                upstream.getOutputStream().flush()
            }
            client.soTimeout = 0
            pipe(client, input, upstream)
        } catch (_: Exception) {
        } finally {
            try { upstream?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** Copia en los dos sentidos hasta que uno de los lados se cierre. */
    private fun pipe(client: Socket, clientIn: InputStream, upstream: Socket) {
        val t = Thread({
            try {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = clientIn.read(buf)
                    if (n < 0) break
                    upstream.getOutputStream().write(buf, 0, n)
                    upstream.getOutputStream().flush()
                }
                try { upstream.shutdownOutput() } catch (_: Exception) {}
            } catch (_: Exception) {
                try { upstream.close() } catch (_: Exception) {}
            }
        }, "proxy-c2u").apply { isDaemon = true }
        t.start()
        try {
            val buf = ByteArray(16 * 1024)
            val up = upstream.getInputStream()
            val out = client.getOutputStream()
            while (true) {
                val n = up.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { upstream.close() } catch (_: Exception) {}
        }
    }
}
