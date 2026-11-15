package com.castbrowser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** Proxy SOCKS5 de mentira: anota lo que se le pide y reenvía todo a un servidor local. */
class FakeSocks5(private val redirectPort: Int, private val auth: Pair<String, String>? = null) {
    private val server = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<String>()   // "tipo:host:puerto"

    init {
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                Thread { try { handle(s) } catch (_: Exception) { try { s.close() } catch (_: Exception) {} } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(s: Socket) {
        val inp = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        inp.readUnsignedByte()
        val methods = ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }
        if (auth != null) {
            if (!methods.contains(2)) { out.write(byteArrayOf(5, 0xFF.toByte())); s.close(); return }
            out.write(byteArrayOf(5, 2))
            inp.readUnsignedByte()
            val u = String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) })
            val p = String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) })
            if (u != auth.first || p != auth.second) { out.write(byteArrayOf(1, 1)); s.close(); return }
            out.write(byteArrayOf(1, 0))
        } else {
            out.write(byteArrayOf(5, 0))
        }
        inp.readUnsignedByte(); inp.readUnsignedByte(); inp.readUnsignedByte()
        val atyp = inp.readUnsignedByte()
        val host = when (atyp) {
            1 -> ByteArray(4).also { inp.readFully(it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
            3 -> String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) })
            else -> "?"
        }
        val port = (inp.readUnsignedByte() shl 8) or inp.readUnsignedByte()
        requests.add("$atyp:$host:$port")
        if (host == "refuse.test") { out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); s.close(); return }
        val target = Socket(InetAddress.getLoopbackAddress(), redirectPort)
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        Thread {
            try { target.getInputStream().copyTo(out) } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
        }.start()
        try { s.getInputStream().copyTo(target.getOutputStream()) } catch (_: Exception) {}
        try { target.close() } catch (_: Exception) {}
    }

    fun close() = server.close()
}

class Socks5Test {
    private val closers = ArrayList<() -> Unit>()

    @After
    fun cleanup() { closers.forEach { try { it() } catch (_: Exception) {} } }

    private fun echoServer(): ServerSocket {
        val srv = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        closers.add { srv.close() }
        Thread {
            while (!srv.isClosed) {
                val c = try { srv.accept() } catch (_: Exception) { break }
                Thread { try { c.getInputStream().copyTo(c.getOutputStream()) } catch (_: Exception) {} }.start()
            }
        }.apply { isDaemon = true }.start()
        return srv
    }

    private fun socks(redirect: Int, auth: Pair<String, String>? = null) = FakeSocks5(redirect, auth).also { closers.add { it.close() } }

    private fun roundTrip(s: Socket, text: String): String {
        s.soTimeout = 5000
        s.getOutputStream().write(text.toByteArray()); s.getOutputStream().flush()
        val buf = ByteArray(text.length)
        DataInputStream(s.getInputStream()).readFully(buf)
        return String(buf)
    }

    @Test
    fun connectsWithoutPasswordAndSendsTheNameToTheProxy() {
        val echo = echoServer()
        val px = socks(echo.localPort)
        Socks5.connect(Socks5Config("127.0.0.1", px.port), "video.example.com", 8088).use {
            assertEquals("hola", roundTrip(it, "hola"))
        }
        // El nombre va al proxy (tipo 3): el móvil no lo resuelve él mismo.
        assertEquals(listOf("3:video.example.com:8088"), px.requests.toList())
    }

    @Test
    fun ipAddressesGoAsAddresses() {
        val px = socks(echoServer().localPort)
        Socks5.connect(Socks5Config("127.0.0.1", px.port), "192.0.2.10", 80).close()
        assertEquals(listOf("1:192.0.2.10:80"), px.requests.toList())
    }

    @Test
    fun usernameAndPassword() {
        val echo = echoServer()
        val px = socks(echo.localPort, "ana" to "s3creto")
        Socks5.connect(Socks5Config("127.0.0.1", px.port, "ana", "s3creto"), "x.example", 443).use {
            assertEquals("ok", roundTrip(it, "ok"))
        }
        val wrong = try { Socks5.connect(Socks5Config("127.0.0.1", px.port, "ana", "mala"), "x.example", 443); "" }
        catch (e: Socks5Exception) { e.message.orEmpty() }
        assertTrue(wrong, wrong.contains("incorrectos"))
        val none = try { Socks5.connect(Socks5Config("127.0.0.1", px.port), "x.example", 443); "" }
        catch (e: Socks5Exception) { e.message.orEmpty() }
        assertTrue(none, none.contains("usuario y contraseña"))
    }

    @Test
    fun errorsAreExplainedInSpanish() {
        val px = socks(echoServer().localPort)
        val refused = try { Socks5.connect(Socks5Config("127.0.0.1", px.port), "refuse.test", 80); "" }
        catch (e: Socks5Exception) { e.message.orEmpty() }
        assertTrue(refused, refused.contains("rechazada por el destino"))
        val down = try { Socks5.connect(Socks5Config("127.0.0.1", 1), "x.example", 80, 1500); "" }
        catch (e: Exception) { e.message.orEmpty() }
        assertTrue(down, down.contains("no se puede conectar con el proxy"))
    }

    @Test
    fun exitIpIsReadThroughTheProxy() {
        val web = FakeHttp { _, _, _, _ -> Triple(200, "text/plain", "203.0.113.7\n") }
        closers.add { web.close() }
        val px = socks(URL(web.base).port)
        assertEquals("203.0.113.7", Socks5.checkExitIp(Socks5Config("127.0.0.1", px.port)))
        assertEquals(listOf("3:api.ipify.org:80"), px.requests.toList())
    }

    private fun localProxy(cfg: Socks5Config) = LocalProxy(cfg).also { it.start(); closers.add { it.close() } }

    @Test
    fun httpsTunnelThroughTheLocalProxy() {
        val echo = echoServer()
        val px = socks(echo.localPort)
        val lp = localProxy(Socks5Config("127.0.0.1", px.port))
        Socket(InetAddress.getLoopbackAddress(), lp.port).use { c ->
            c.soTimeout = 5000
            c.getOutputStream().write("CONNECT secure.example.com:443 HTTP/1.1\r\nHost: secure.example.com:443\r\n\r\n".toByteArray())
            c.getOutputStream().flush()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(c.getInputStream().read().toChar())
            assertTrue(head.toString(), head.startsWith("HTTP/1.1 200"))
            assertEquals("datos", roundTrip(c, "datos"))
        }
        assertEquals(listOf("3:secure.example.com:443"), px.requests.toList())
    }

    @Test
    fun plainHttpThroughTheLocalProxyArrivesAsNormalRequest() {
        val seen = CopyOnWriteArrayList<String>()
        val web = FakeHttp { m, path, h, _ ->
            seen.add("$m $path host=${h["host"]} proxyConn=${h["proxy-connection"]}")
            Triple(200, "text/plain", "hola desde la web")
        }
        closers.add { web.close() }
        val px = socks(URL(web.base).port)
        val lp = localProxy(Socks5Config("127.0.0.1", px.port))
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", lp.port))
        val c = URL("http://sitio.example/a/b?q=1").openConnection(proxy) as HttpURLConnection
        c.setRequestProperty("Proxy-Connection", "keep-alive")
        assertEquals(200, c.responseCode)
        assertEquals("hola desde la web", c.inputStream.readBytes().toString(Charsets.UTF_8))
        assertEquals(listOf("GET /a/b?q=1 host=sitio.example proxyConn=null"), seen.toList())
        assertEquals(listOf("3:sitio.example:80"), px.requests.toList())
    }

    @Test
    fun ifTheProxyIsDownTheBrowserGetsA502NotADirectConnection() {
        val lp = localProxy(Socks5Config("127.0.0.1", 1))
        Socket(InetAddress.getLoopbackAddress(), lp.port).use { c ->
            c.soTimeout = 8000
            c.getOutputStream().write("CONNECT x.example:443 HTTP/1.1\r\n\r\n".toByteArray())
            c.getOutputStream().flush()
            val text = c.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(text, text.startsWith("HTTP/1.1 502") && text.contains("no se puede conectar con el proxy"))
        }
    }

    @Test
    fun videoDownloadsUseTheProxyOnlyWhenSet() {
        val web = FakeHttp { _, _, _, _ -> Triple(200, "text/plain", "ok") }
        closers.add { web.close() }
        val px = socks(URL(web.base).port)
        val lp = localProxy(Socks5Config("127.0.0.1", px.port))
        try {
            Upstream.proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", lp.port))
            val c = Upstream.open("http://cdn.example/seg.ts")
            assertEquals(200, c.responseCode)
            assertEquals(listOf("3:cdn.example:80"), px.requests.toList())
        } finally {
            Upstream.proxy = null
        }
        // Sin proxy se conecta directamente a la dirección real.
        assertEquals(200, Upstream.open("${web.base}/x").responseCode)
        assertEquals(1, px.requests.size)
    }
}
