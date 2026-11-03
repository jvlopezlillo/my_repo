package com.castbrowser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** Servidor HTTP mínimo para simular aparatos en los tests. */
class FakeHttp(val handler: (method: String, path: String, headers: Map<String, String>, body: String) -> Triple<Int, String, String>) {
    private val server = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
    val base = "http://127.0.0.1:${server.localPort}"

    init {
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                Thread {
                    s.use { sock ->
                        val input = sock.getInputStream()
                        fun line(): String {
                            val sb = StringBuilder()
                            while (true) {
                                val c = input.read()
                                if (c < 0 || c == '\n'.code) break
                                if (c != '\r'.code) sb.append(c.toChar())
                            }
                            return sb.toString()
                        }
                        val req = line().split(' ')
                        val h = HashMap<String, String>()
                        while (true) {
                            val l = line()
                            if (l.isEmpty()) break
                            h[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
                        }
                        val len = h["content-length"]?.toInt() ?: 0
                        val body = ByteArray(len)
                        var read = 0
                        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
                        val (code, type, text) = handler(req[0], req[1], h, String(body, Charsets.UTF_8))
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        sock.getOutputStream().apply {
                            write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun close() = server.close()
}

class RendererTest {
    private val opener: HttpOpener = { u -> URL(u).openConnection() as HttpURLConnection }

    private val description = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[TV] Salón &amp; cocina</friendlyName>
            <modelName>UE55</modelName>
            <UDN>uuid:1234</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>upnp/control/AVTransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>"""

    @Test
    fun parsesDlnaDescription() {
        val info = Dlna.parseDescription(description, "http://192.168.1.20:9197/dmr")!!
        assertEquals("[TV] Salón & cocina", info.name)
        assertEquals("uuid:1234", info.udn)
        assertEquals("http://192.168.1.20:9197/upnp/control/AVTransport1", info.avControlUrl)
        assertEquals("http://192.168.1.20:9197/upnp/control/RenderingControl1", info.rcControlUrl)
        assertEquals("http://x/desc.xml", Dlna.location("HTTP/1.1 200 OK\r\nLOCATION: http://x/desc.xml\r\nST: a\r\n"))
        assertEquals(3723.5, Dlna.parseTime("1:02:03.5"), 0.01)
        assertEquals("0:01:05", Dlna.formatTime(65.0))
    }

    @Test
    fun dlnaLoadsAndPlays() {
        val actions = CopyOnWriteArrayList<String>()
        var uri = ""
        var state = "NO_MEDIA_PRESENT"
        val tv = FakeHttp { _, _, h, body ->
            val action = h["soapaction"]!!.trim('"').substringAfter('#')
            actions.add(action)
            val reply = when (action) {
                "SetAVTransportURI" -> { uri = Dlna.tag(body, "CurrentURI")!!; state = "STOPPED"; "" }
                "Play" -> { state = "TRANSITIONING"; Thread { Thread.sleep(1200); state = "PLAYING" }.start(); "" }
                "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"
                "GetPositionInfo" -> "<TrackDuration>0:10:00</TrackDuration><RelTime>0:00:03</RelTime>"
                "GetVolume" -> "<CurrentVolume>30</CurrentVolume>"
                else -> ""
            }
            Triple(200, "text/xml", "<s:Envelope><s:Body><u:${action}Response>$reply</u:${action}Response></s:Body></s:Envelope>")
        }
        try {
            val info = DlnaInfo("TV", "uuid:1", null, "${tv.base}/av", Dlna.AV_TRANSPORT,
                "${tv.base}/rc", "urn:schemas-upnp-org:service:RenderingControl:1")
            val r = DlnaRenderer(info, opener, {}, {})
            r.connect()
            assertEquals(0.3, r.lastStatus.volume, 0.01)
            r.load("http://10.0.0.2:5000/o1/v.mp4?a=1&b=2", "video/mp4", "Peli <1>", 0.0, false, false, emptyMap())
            assertEquals("http://10.0.0.2:5000/o1/v.mp4?a=1&b=2", uri)
            assertEquals("PLAYING", r.lastStatus.playerState)
            assertEquals(600.0, r.lastStatus.duration, 0.1)
            assertTrue(actions.indexOf("SetAVTransportURI") < actions.indexOf("Play"))
            r.stop()
        } finally {
            tv.close()
        }
    }

    @Test
    fun dlnaToleratesSlowSetUriButTimesOutClearly() {
        val delayMs = longArrayOf(900)
        val tv = FakeHttp { _, _, h, _ ->
            val action = h["soapaction"]!!.trim('"').substringAfter('#')
            if (action == "SetAVTransportURI") Thread.sleep(delayMs[0])
            val reply = if (action == "GetTransportInfo") "<CurrentTransportState>PLAYING</CurrentTransportState>" else ""
            Triple(200, "text/xml", "<Envelope><Body>$reply</Body></Envelope>")
        }
        try {
            val info = DlnaInfo("TV", "u", null, "${tv.base}/av", Dlna.AV_TRANSPORT, null, null)
            // Más lenta que el límite normal de 8 s de otras acciones no hace falta: con 3 s de margen pasa.
            DlnaRenderer(info, opener, {}, {}, slowActionTimeoutMs = 3000)
                .load("http://x/v.m3u8", "application/x-mpegURL", "t", 0.0, false, false, emptyMap())

            delayMs[0] = 1500
            val msg = try {
                DlnaRenderer(info, opener, {}, {}, slowActionTimeoutMs = 400)
                    .load("http://x/v.m3u8", "application/x-mpegURL", "t", 0.0, false, false, emptyMap())
                ""
            } catch (e: CastException) { e.message.orEmpty() }
            assertTrue(msg, msg.contains("no contestó a SetAVTransportURI"))
        } finally {
            tv.close()
        }
    }

    @Test
    fun dlnaReportsFailureWhenTvStaysStopped() {
        val tv = FakeHttp { _, _, h, _ ->
            val action = h["soapaction"]!!.trim('"').substringAfter('#')
            val reply = if (action == "GetTransportInfo") "<CurrentTransportState>STOPPED</CurrentTransportState>" else ""
            Triple(200, "text/xml", "<Envelope><Body>$reply</Body></Envelope>")
        }
        try {
            val r = DlnaRenderer(DlnaInfo("TV", "u", null, "${tv.base}/av", Dlna.AV_TRANSPORT, null, null), opener, {}, {})
            val failed = try { r.load("http://x/v.mp4", "video/mp4", "t", 0.0, false, false, emptyMap()); false }
            catch (_: CastException) { true }
            assertTrue(failed)
        } finally {
            tv.close()
        }
    }

    @Test
    fun tvReceiverProtocol() {
        var played: JSONObject? = null
        var state = "idle"
        val controls = CopyOnWriteArrayList<String>()
        val tv = FakeHttp { method, path, _, body ->
            when {
                path == "/info" -> Triple(200, "application/json", """{"app":"castbrowser-tv","name":"MIBOX3","version":"1"}""")
                path == "/play" && method == "POST" -> {
                    played = JSONObject(body); state = "loading"
                    Thread { Thread.sleep(1000); state = "playing" }.start()
                    Triple(200, "application/json", """{"ok":true}""")
                }
                path == "/status" -> Triple(200, "application/json",
                    """{"state":"$state","position":12.5,"duration":100,"volume":0.5,"video":"1920x1080"}""")
                path == "/control" -> { controls.add(JSONObject(body).getString("action")); Triple(200, "application/json", "{}") }
                else -> Triple(404, "text/plain", "")
            }
        }
        try {
            val port = URL(tv.base).port
            var closed = false
            val logs = CopyOnWriteArrayList<String>()
            val r = TvReceiverRenderer("127.0.0.1", port, opener, {}, { closed = true }, { logs.add(it) })
            r.connect()
            r.load("https://cdn/x.m3u8", "application/x-mpegURL", "Peli", 30.0, false, false,
                mapOf("Referer" to "https://page/", "Cookie" to "a=1"))
            assertEquals("https://cdn/x.m3u8", played!!.getString("url"))
            assertEquals("a=1", played!!.getJSONObject("headers").getString("Cookie"))
            assertEquals(30.0, played!!.getDouble("position"), 0.01)
            assertEquals("PLAYING", r.lastStatus.playerState)
            assertEquals(100.0, r.lastStatus.duration, 0.01)
            assertTrue(logs.toString(), logs.any { it.contains("reproduciendo a 1920x1080") })
            r.stop()
            assertTrue(closed)
            assertTrue(controls.contains("stop"))
        } finally {
            tv.close()
        }
    }
}
