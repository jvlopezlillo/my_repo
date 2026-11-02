package com.castbrowser.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory

class FakePlayer : DlnaPlayer {
    @Volatile var uri = ""
    @Volatile var meta = ""
    @Volatile var state = "NO_MEDIA_PRESENT"
    @Volatile var pos = 0.0
    @Volatile var dur = 0.0
    @Volatile var vol = 30
    @Volatile var mute = false
    val calls = CopyOnWriteArrayList<String>()

    override fun setUri(uri: String, metadata: String) {
        this.uri = uri; meta = metadata; state = "STOPPED"; calls.add("setUri")
    }
    override fun play() { state = "PLAYING"; dur = 600.0; calls.add("play") }
    override fun pause() { state = "PAUSED_PLAYBACK"; calls.add("pause") }
    override fun stop() { state = "STOPPED"; calls.add("stop") }
    override fun seek(seconds: Double) { pos = seconds; calls.add("seek:$seconds") }
    override fun volume() = vol
    override fun setVolume(percent: Int) { vol = percent }
    override fun muted() = mute
    override fun setMuted(mute: Boolean) { this.mute = mute }
    override fun transportState() = state
    override fun position() = pos
    override fun duration() = dur
    override fun currentUri() = uri
    override fun currentMetadata() = meta
}

class DlnaCoreTest {
    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun xml(text: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())

    private fun soap(core: DlnaCore, service: String, action: String, args: String = ""): HttpReply {
        val type = "urn:schemas-upnp-org:service:$service:1"
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$type\">" +
            "$args</u:$action></s:Body></s:Envelope>"
        return core.handle("POST", "/dlna/$service/control", mapOf("soapaction" to "\"$type#$action\""), body)
    }

    private fun tag(text: String, name: String) =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.let { Xml.unescape(it) }

    @Test
    fun descriptionAndScpdAreValidXml() {
        val core = DlnaCore("Salón (DLNA)", uuid, FakePlayer())
        val desc = core.handle("GET", "/dlna/desc.xml", emptyMap(), "")
        assertEquals(200, desc.code)
        val doc = xml(desc.body)
        assertEquals("Salón (DLNA)", doc.getElementsByTagName("friendlyName").item(0).textContent)
        assertEquals("uuid:$uuid", doc.getElementsByTagName("UDN").item(0).textContent)
        assertEquals(3, doc.getElementsByTagName("service").length)
        for (svc in listOf("AVTransport", "RenderingControl", "ConnectionManager")) {
            val scpd = core.handle("GET", "/dlna/$svc.xml", emptyMap(), "")
            assertEquals(svc, 200, scpd.code)
            assertTrue(xml(scpd.body).getElementsByTagName("action").length >= 3)
        }
        val names = xml(core.handle("GET", "/dlna/AVTransport.xml", emptyMap(), "").body)
            .getElementsByTagName("name")
        val all = (0 until names.length).map { names.item(it).textContent }
        assertTrue(all.containsAll(listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetPositionInfo")))
        assertEquals(404, core.handle("GET", "/nada", emptyMap(), "").code)
    }

    @Test
    fun playbackFlow() {
        val p = FakePlayer()
        val core = DlnaCore("TV", uuid, p)
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><item id=\"0\"><dc:title>Película &amp; más</dc:title>" +
            "<res protocolInfo=\"http-get:*:video/mp4:*\">http://x/v.mp4?a=1&amp;b=2</res></item></DIDL-Lite>"
        val set = soap(core, "AVTransport", "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>http://x/v.mp4?a=1&amp;b=2</CurrentURI>" +
                "<CurrentURIMetaData>${Xml.escape(didl)}</CurrentURIMetaData>")
        assertEquals(200, set.code)
        xml(set.body)
        assertEquals("http://x/v.mp4?a=1&b=2", p.uri)
        assertEquals(didl, p.meta)
        assertEquals("Película & más", Didl.title(p.meta))
        assertEquals("video/mp4", Didl.mime(p.meta))

        assertEquals("STOPPED", tag(soap(core, "AVTransport", "GetTransportInfo", "<InstanceID>0</InstanceID>").body, "CurrentTransportState"))
        assertEquals(200, soap(core, "AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>").code)
        val info = soap(core, "AVTransport", "GetTransportInfo", "<InstanceID>0</InstanceID>").body
        assertEquals("PLAYING", tag(info, "CurrentTransportState"))

        p.pos = 65.0
        val pos = soap(core, "AVTransport", "GetPositionInfo", "<InstanceID>0</InstanceID>").body
        xml(pos)
        assertEquals("0:01:05", tag(pos, "RelTime"))
        assertEquals("0:10:00", tag(pos, "TrackDuration"))
        assertEquals(didl, tag(pos, "TrackMetaData"))
        assertEquals("http://x/v.mp4?a=1&b=2", tag(pos, "TrackURI"))

        soap(core, "AVTransport", "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:02:30</Target>")
        assertTrue(p.calls.contains("seek:150.0"))
        assertEquals(500, soap(core, "AVTransport", "Seek", "<InstanceID>0</InstanceID><Unit>FOO</Unit><Target>1</Target>").code)

        soap(core, "AVTransport", "Pause", "<InstanceID>0</InstanceID>")
        assertEquals("PAUSED_PLAYBACK", tag(soap(core, "AVTransport", "GetTransportInfo", "<InstanceID>0</InstanceID>").body, "CurrentTransportState"))
        soap(core, "AVTransport", "Stop", "<InstanceID>0</InstanceID>")
        assertEquals(listOf("setUri", "play", "seek:150.0", "pause", "stop"), p.calls.toList())
    }

    @Test
    fun volumeMuteAndErrors() {
        val p = FakePlayer()
        val core = DlnaCore("TV", uuid, p)
        assertEquals("30", tag(soap(core, "RenderingControl", "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>").body, "CurrentVolume"))
        soap(core, "RenderingControl", "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>250</DesiredVolume>")
        assertEquals(100, p.vol)
        soap(core, "RenderingControl", "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>1</DesiredMute>")
        assertTrue(p.mute)
        assertEquals("1", tag(soap(core, "RenderingControl", "GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>").body, "CurrentMute"))

        val bad = soap(core, "AVTransport", "Teleport")
        assertEquals(500, bad.code)
        assertEquals("401", tag(bad.body, "errorCode"))
        xml(bad.body)

        val sink = tag(soap(core, "ConnectionManager", "GetProtocolInfo").body, "Sink")!!
        assertTrue(sink.contains("http-get:*:video/mp4:*"))
        assertTrue(sink.contains("application/vnd.apple.mpegurl"))
    }

    @Test
    fun soapActionMissingFallsBackToBody() {
        val core = DlnaCore("TV", uuid, FakePlayer())
        val body = "<s:Envelope xmlns:s=\"x\"><s:Body><u:GetTransportInfo xmlns:u=\"y\"><InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>"
        val r = core.handle("POST", "/dlna/AVTransport/control", emptyMap(), body)
        assertEquals(200, r.code)
        assertEquals("NO_MEDIA_PRESENT", tag(r.body, "CurrentTransportState"))
    }

    @Test
    fun eventsAreSentOnSubscribeAndOnChange() {
        val p = FakePlayer()
        val sent = CopyOnWriteArrayList<Triple<String, Map<String, String>, String>>()
        var now = 1_000_000L
        val core = DlnaCore("TV", uuid, p, { url, h, b -> sent.add(Triple(url, h, b)); true }, { now })

        val sub = core.handle("SUBSCRIBE", "/dlna/AVTransport/event",
            mapOf("callback" to "<http://10.0.0.5:9000/cb>", "nt" to "upnp:event", "timeout" to "Second-300"), "")
        assertEquals(200, sub.code)
        val sid = sub.headers["SID"]!!
        assertTrue(sid.startsWith("uuid:"))
        assertEquals("Second-1800", sub.headers["TIMEOUT"])

        core.pollEvents()
        assertEquals(1, sent.size)
        assertEquals("http://10.0.0.5:9000/cb", sent[0].first)
        assertEquals(sid, sent[0].second["SID"])
        assertEquals("0", sent[0].second["SEQ"])
        assertTrue(Xml.unescape(sent[0].third).contains("TransportState val=\"NO_MEDIA_PRESENT\""))
        xml(sent[0].third)

        core.pollEvents()                    // sin cambios: no hay aviso
        assertEquals(1, sent.size)

        p.state = "PLAYING"
        core.pollEvents()
        assertEquals(2, sent.size)
        assertEquals("1", sent[1].second["SEQ"])
        assertTrue(Xml.unescape(sent[1].third).contains("TransportState val=\"PLAYING\""))

        // La posición no se avisa por evento.
        p.pos = 42.0
        core.pollEvents()
        assertEquals(2, sent.size)

        // Renovar y cancelar
        assertEquals(200, core.handle("SUBSCRIBE", "/dlna/AVTransport/event", mapOf("sid" to sid), "").code)
        assertEquals(412, core.handle("SUBSCRIBE", "/dlna/AVTransport/event", mapOf("sid" to "uuid:nope"), "").code)
        assertEquals(200, core.handle("UNSUBSCRIBE", "/dlna/AVTransport/event", mapOf("sid" to sid), "").code)
        assertEquals(0, core.subscriberCount())
        assertEquals(412, core.handle("SUBSCRIBE", "/dlna/AVTransport/event", mapOf("nt" to "upnp:event"), "").code)
    }

    @Test
    fun deadSubscribersAreDropped() {
        val p = FakePlayer()
        var now = 0L
        val core = DlnaCore("TV", uuid, p, { _, _, _ -> false }, { now })
        core.handle("SUBSCRIBE", "/dlna/RenderingControl/event", mapOf("callback" to "<http://10.0.0.9/x>"), "")
        repeat(4) { core.pollEvents() }
        assertEquals(0, core.subscriberCount())

        core.handle("SUBSCRIBE", "/dlna/RenderingControl/event", mapOf("callback" to "<http://10.0.0.9/x>"), "")
        now += 2_000_000
        core.pollEvents()
        assertEquals(0, core.subscriberCount())
    }

    @Test
    fun ssdp() {
        val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals(DlnaCore.DEVICE, Ssdp.searchTarget(search))
        assertNull(Ssdp.searchTarget("NOTIFY * HTTP/1.1\r\nNT: x\r\n\r\n"))
        assertNull(Ssdp.searchTarget("M-SEARCH * HTTP/1.1\r\nST: x\r\n\r\n"))

        assertEquals(listOf(DlnaCore.DEVICE), Ssdp.matching(DlnaCore.DEVICE, uuid))
        assertEquals(6, Ssdp.matching("ssdp:all", uuid).size)
        assertTrue(Ssdp.matching("urn:schemas-upnp-org:device:MediaServer:1", uuid).isEmpty())

        val r = String(Ssdp.response(DlnaCore.AVT, "http://10.0.0.2:49152/dlna/desc.xml", uuid), Charsets.ISO_8859_1)
        assertTrue(r.startsWith("HTTP/1.1 200 OK"))
        assertTrue(r.contains("LOCATION: http://10.0.0.2:49152/dlna/desc.xml"))
        assertTrue(r.contains("USN: uuid:$uuid::${DlnaCore.AVT}"))
        assertTrue(String(Ssdp.response("uuid:$uuid", "x", uuid)).contains("USN: uuid:$uuid\r\n"))

        val bye = String(Ssdp.notify("upnp:rootdevice", "http://x", uuid, "ssdp:byebye"))
        assertTrue(bye.startsWith("NOTIFY * HTTP/1.1"))
        assertTrue(bye.contains("NTS: ssdp:byebye"))
        assertFalse(bye.contains("LOCATION"))
    }

    @Test
    fun subnetsAndTimes() {
        val a = byteArrayOf(192.toByte(), 168.toByte(), 43, 1)
        assertTrue(sameSubnet(a, byteArrayOf(192.toByte(), 168.toByte(), 43, 200.toByte()), 24))
        assertFalse(sameSubnet(a, byteArrayOf(192.toByte(), 168.toByte(), 1, 200.toByte()), 24))
        assertTrue(sameSubnet(a, byteArrayOf(192.toByte(), 168.toByte(), 1, 5), 16))
        assertEquals(3723.5, parseTime("1:02:03.5"), 0.001)
        assertEquals(0.0, parseTime("NOT_IMPLEMENTED"), 0.0)
        assertEquals("1:02:03", fmtTime(3723.9))
    }

    @Test
    fun realHttpServerSoapAndNotify() {
        val p = FakePlayer()
        val received = CopyOnWriteArrayList<String>()
        // Servidor que hace de controlador: recibe los NOTIFY.
        val controller = SimpleHttpServer(0) { m, _, h, b -> received.add("$m|${h["sid"]}|$b"); HttpReply(200, "text/plain") }
        controller.start()
        val core = DlnaCore("TV", uuid, p, { url, h, b -> RawHttp.send("NOTIFY", url, h, b) == 200 })
        val tv = SimpleHttpServer(0) { m, path, h, b -> core.handle(m, path, h, b) }
        tv.start()
        try {
            val base = "http://127.0.0.1:${tv.port}"
            val desc = URL("$base/dlna/desc.xml").openConnection() as HttpURLConnection
            assertEquals(200, desc.responseCode)
            assertTrue(desc.inputStream.readBytes().toString(Charsets.UTF_8).contains("MediaRenderer"))

            val post = URL("$base/dlna/AVTransport/control").openConnection() as HttpURLConnection
            post.requestMethod = "POST"
            post.doOutput = true
            post.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
            post.outputStream.use {
                it.write(("<s:Envelope xmlns:s=\"x\"><s:Body><u:Play xmlns:u=\"y\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>").toByteArray())
            }
            assertEquals(200, post.responseCode)
            // el servidor llama a play() aunque no haya URI cargada en el FakePlayer
            assertTrue(p.calls.contains("play"))

            // SUBSCRIBE con el método real (HttpURLConnection no lo permite: usamos RawHttp)
            val code = RawHttp.send("SUBSCRIBE", "$base/dlna/AVTransport/event",
                mapOf("CALLBACK" to "<http://127.0.0.1:${controller.port}/cb>", "NT" to "upnp:event", "TIMEOUT" to "Second-300"), "")
            assertEquals(200, code)
            core.pollEvents()
            assertEquals(1, received.size)
            assertTrue(received[0].startsWith("NOTIFY|uuid:"))
            assertTrue(Xml.unescape(received[0]).contains("TransportState val=\"PLAYING\""))
        } finally {
            tv.close(); controller.close()
        }
        assertNotNull(core)
    }
}
