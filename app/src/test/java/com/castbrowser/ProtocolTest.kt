package com.castbrowser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

class ProtocolTest {

    @Test
    fun castMessageRoundTrip() {
        val m = CastMessage("sender-0", "receiver-0", CastNs.RECEIVER, """{"type":"LAUNCH","appId":"CC1AD845"}""")
        val out = ByteArrayOutputStream()
        CastMessage.writeFramed(out, m)
        val back = CastMessage.readFramed(DataInputStream(out.toByteArray().inputStream()))
        assertEquals(m, back)
    }

    @Test
    fun castMessageLongPayload() {
        val payload = "x".repeat(70_000) + "ñ"
        val m = CastMessage("a", "b", CastNs.MEDIA, payload)
        assertEquals(m, CastMessage.decode(m.encode()))
    }

    // ------------------------------------------------------------ mDNS

    private fun name(out: ByteArrayOutputStream, n: String) {
        n.split('.').forEach { val b = it.toByteArray(); out.write(b.size); out.write(b) }
        out.write(0)
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) { out.write(v ushr 8); out.write(v and 0xFF) }

    private fun record(out: ByteArrayOutputStream, n: String, type: Int, rdata: ByteArray) {
        name(out, n); u16(out, type); u16(out, 0x8001); u16(out, 0); u16(out, 120); u16(out, rdata.size); out.write(rdata)
    }

    @Test
    fun parsesChromecastResponse() {
        val inst = "Chromecast-abc123._googlecast._tcp.local"
        val out = ByteArrayOutputStream()
        u16(out, 0); u16(out, 0x8400); u16(out, 0); u16(out, 1); u16(out, 0); u16(out, 3)
        record(out, Mdns.SERVICE, 12, ByteArrayOutputStream().also { name(it, inst) }.toByteArray())
        val txt = ByteArrayOutputStream()
        listOf("id=abc123", "md=Chromecast", "fn=Tele del salón").forEach {
            val b = it.toByteArray(); txt.write(b.size); txt.write(b)
        }
        record(out, inst, 16, txt.toByteArray())
        val srv = ByteArrayOutputStream()
        u16(srv, 0); u16(srv, 0); u16(srv, 8009); name(srv, "abc123.local")
        record(out, inst, 33, srv.toByteArray())
        record(out, "abc123.local", 1, byteArrayOf(192.toByte(), 168.toByte(), 1, 50))

        val found = Mdns.parseResponse(out.toByteArray())
        assertEquals(1, found.size)
        val c = found[0]
        assertEquals("Tele del salón", c.name)
        assertEquals("192.168.1.50", c.ip)
        assertEquals(8009, c.port)
        assertEquals("abc123", c.id)
    }

    @Test
    fun ignoresQueries() {
        assertTrue(Mdns.parseResponse(Mdns.buildQuery()).isEmpty())
    }

    // ------------------------------------------------------- Detección

    @Test
    fun classifiesUrls() {
        assertEquals("hls", MediaDetector.classify("https://cdn.x.com/v/master.m3u8?token=1"))
        assertEquals("dash", MediaDetector.classify("https://cdn.x.com/v/manifest.mpd"))
        assertEquals("file", MediaDetector.classify("https://x.com/movie.mp4"))
        assertNull(MediaDetector.classify("https://cdn.x.com/v/seg-12.ts"))
        assertNull(MediaDetector.classify("https://cdn.x.com/v/chunk-3.m4s"))
        assertNull(MediaDetector.classify("https://x.com/app.js"))
        assertNull(MediaDetector.classify("blob:https://x.com/1234"))
    }

    @Test
    fun candidatesPreferVideoSrcThenHls() {
        val log = CaptureLog()
        log.add("https://x.com/a.mp4", emptyMap())
        log.add("https://x.com/master.m3u8", mapOf("Referer" to "https://x.com/"))
        log.add("https://x.com/720.m3u8", emptyMap())
        val blob = PageVideo("blob:https://x.com/1", true, 0.0, 0.0, false, "", "https://x.com/")
        assertEquals("https://x.com/master.m3u8", log.candidates(blob).first().url)
        val direct = blob.copy(src = "https://y.com/v.mp4")
        assertEquals("https://y.com/v.mp4", log.candidates(direct).first().url)
    }

    // ------------------------------------------------------------ Proxy

    @Test
    fun rewritesHls() {
        val r = ProxyRewriter { "http://10.0.0.2:5000" }
        val body = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXT-X-MAP:URI="https://other.com/init.mp4"
            #EXTINF:4.0,
            seg1.ts?x=1
            /abs/seg2.ts
        """.trimIndent()
        val out = r.rewriteM3u8(body, "https://cdn.com/path/index.m3u8").lines()
        assertTrue(out[1], out[1].contains("URI=\"http://10.0.0.2:5000/o1/path/key.bin\""))
        assertTrue(out[2], out[2].contains("URI=\"http://10.0.0.2:5000/o2/init.mp4\""))
        assertEquals("http://10.0.0.2:5000/o1/path/seg1.ts?x=1", out[4])
        assertEquals("http://10.0.0.2:5000/o1/abs/seg2.ts", out[5])
    }

    @Test
    fun proxyServesRewrittenPlaylistAndRanges() {
        val seenReferer = arrayOfNulls<String>(1)
        val seenMethods = java.util.concurrent.CopyOnWriteArrayList<String>()
        // Servidor HTTP mínimo (el de la JDK no está disponible en los tests de Android).
        val server = java.net.ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                s.use { sock ->
                    val r = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val requestLine = r.readLine().split(' ')
                    val path = requestLine[1]
                    if (path == "/v/seg.ts") seenMethods.add(requestLine[0])
                    val h = HashMap<String, String>()
                    while (true) {
                        val l = r.readLine() ?: break
                        if (l.isEmpty()) break
                        h[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
                    }
                    val out = sock.getOutputStream()
                    fun respond(code: Int, headers: String, body: ByteArray) {
                        out.write(("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\n${headers}Connection: close\r\n\r\n").toByteArray())
                        out.write(body); out.flush()
                    }
                    if (path == "/v/index.m3u8") {
                        seenReferer[0] = h["referer"]
                        respond(200, "Content-Type: application/vnd.apple.mpegurl\r\n", "#EXTM3U\n#EXTINF:4,\nseg.ts\n".toByteArray())
                    } else {
                        val data = ByteArray(1000) { (it % 256).toByte() }
                        if (h["range"] == "bytes=10-19") respond(206, "Content-Range: bytes 10-19/1000\r\n", data.copyOfRange(10, 20))
                        else respond(200, "", data)
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        val proxy = MediaProxy("127.0.0.1") { "session=1" }
        try {
            val origin = "http://127.0.0.1:${server.localPort}"
            val url = proxy.rewriter.proxify("$origin/v/index.m3u8", mapOf("Referer" to "https://page.com/"))
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("Connection", "close")
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
            assertEquals("https://page.com/", seenReferer[0])
            val segUrl = text.lines().first { it.startsWith("http") }
            assertTrue(segUrl, segUrl.startsWith(proxy.baseUrl + "/o1/v/seg.ts"))

            val s = URL(segUrl).openConnection() as HttpURLConnection
            s.setRequestProperty("Range", "bytes=10-19")
            assertEquals(206, s.responseCode)
            val bytes = s.inputStream.use { it.readBytes() }
            assertEquals(10, bytes.size)
            assertEquals(10.toByte(), bytes[0])

            // HEAD de la tele: a la web se le pide con GET y a la tele se le contesta sin cuerpo.
            seenMethods.clear()
            val head = URL(segUrl).openConnection() as HttpURLConnection
            head.requestMethod = "HEAD"
            assertEquals(200, head.responseCode)
            assertEquals(0, head.inputStream.use { it.readBytes() }.size)
            assertEquals(listOf("GET"), seenMethods.toList())
        } finally {
            proxy.close()
            server.close()
        }
    }

    // ------------------------------------------------------ Cast falso

    /** Chromecast simulado: TLS en un puerto local, responde LAUNCH y LOAD. */
    @Test
    fun castClientLaunchesAndLoads() {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.classLoader!!.getResourceAsStream("fakecast.p12").use { ks.load(it, "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "changeit".toCharArray())
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        val server = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val loaded = arrayOfNulls<JSONObject>(1)
        val gotPong = CountDownLatch(1)

        Thread {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                fun send(src: String, ns: String, json: JSONObject) =
                    CastMessage.writeFramed(out, CastMessage(src, "sender-0", ns, json.toString()))
                send("receiver-0", CastNs.HEARTBEAT, JSONObject().put("type", "PING"))
                try {
                    while (true) {
                        val m = CastMessage.readFramed(input)
                        val j = JSONObject(m.payloadUtf8)
                        when (j.optString("type")) {
                            "PONG" -> gotPong.countDown()
                            "LAUNCH" -> {
                                // Primero un estado sin la app (arrancando) y luego con ella.
                                send("receiver-0", CastNs.RECEIVER, JSONObject().put("type", "RECEIVER_STATUS")
                                    .put("requestId", j.getInt("requestId"))
                                    .put("status", JSONObject().put("applications", org.json.JSONArray())))
                                send("receiver-0", CastNs.RECEIVER, JSONObject().put("type", "RECEIVER_STATUS")
                                    .put("requestId", 0)
                                    .put("status", JSONObject()
                                        .put("volume", JSONObject().put("level", 0.4))
                                        .put("applications", org.json.JSONArray().put(JSONObject()
                                            .put("appId", "CC1AD845").put("transportId", "web-5").put("sessionId", "s1")))))
                            }
                            "LOAD" -> if (j.getJSONObject("media").getString("contentId").contains("fail")) {
                                // Como el Mi Box: estado intermedio "LOADING" y luego fallo.
                                send("web-5", CastNs.MEDIA, JSONObject().put("type", "MEDIA_STATUS")
                                    .put("requestId", j.getInt("requestId"))
                                    .put("status", org.json.JSONArray().put(JSONObject()
                                        .put("mediaSessionId", 2).put("playerState", "IDLE")
                                        .put("extendedStatus", JSONObject().put("playerState", "LOADING")))))
                                send("web-5", CastNs.MEDIA, JSONObject().put("type", "LOAD_FAILED")
                                    .put("requestId", j.getInt("requestId")).put("itemId", 1))
                            } else {
                                loaded[0] = j
                                send("web-5", CastNs.MEDIA, JSONObject().put("type", "MEDIA_STATUS")
                                    .put("requestId", j.getInt("requestId"))
                                    .put("status", org.json.JSONArray().put(JSONObject()
                                        .put("mediaSessionId", 7).put("playerState", "PLAYING")
                                        .put("currentTime", 12.0)
                                        .put("media", JSONObject().put("duration", 100.0)))))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }.start()

        val closed = CountDownLatch(1)
        val client = CastClient(
            "127.0.0.1", server.localPort,
            rawSocket = { h, p -> Socket(h, p) },
            onStatus = {},
            onClosed = { closed.countDown() },
        )
        client.connect()
        val failed = try {
            client.load("https://x.com/fail.mp4", "video/mp4", "Peli", 0.0, live = false, hlsFmp4 = false, headers = emptyMap()); false
        } catch (_: CastException) { true }
        assertTrue("un LOAD_FAILED tras un estado intermedio debe contar como fallo", failed)
        client.load("http://10.0.0.2/o1/index.m3u8", "application/x-mpegURL", "Peli", 12.0, live = false, hlsFmp4 = true, headers = emptyMap())

        assertEquals("web-5", client.transportId)
        assertEquals(7, client.mediaSessionId)
        assertEquals("PLAYING", client.lastStatus.playerState)
        assertEquals(100.0, client.lastStatus.duration, 0.01)
        assertEquals(0.4, client.lastStatus.volume, 0.01)
        val media = loaded[0]!!.getJSONObject("media")
        assertEquals("http://10.0.0.2/o1/index.m3u8", media.getString("contentId"))
        assertEquals("fmp4", media.getString("hlsSegmentFormat"))
        assertEquals("s1", loaded[0]!!.getString("sessionId"))
        assertTrue(gotPong.await(3, TimeUnit.SECONDS))
        assertFalse(closed.count == 0L)

        client.stop()
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        server.close()
    }
}
