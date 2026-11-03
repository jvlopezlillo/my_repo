package com.castbrowser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** Chromecast simulado que deja el vídeo "cargando" de distintas formas. */
class CastLoadTest {
    private fun fakeCast(onLoad: (send: (String, JSONObject) -> Unit, requestId: Int) -> Unit): SSLServerSocket {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.classLoader!!.getResourceAsStream("fakecast.p12").use { ks.load(it, "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        Thread {
            try {
                server.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    fun send(ns: String, j: JSONObject) = CastMessage.writeFramed(out, CastMessage("receiver-0", "sender-0", ns, j.toString()))
                    while (true) {
                        val j = JSONObject(CastMessage.readFramed(input).payloadUtf8)
                        when (j.optString("type")) {
                            "LAUNCH" -> send(CastNs.RECEIVER, JSONObject().put("type", "RECEIVER_STATUS").put("requestId", 0)
                                .put("status", JSONObject().put("applications", JSONArray().put(JSONObject()
                                    .put("appId", "CC1AD845").put("transportId", "web-5").put("sessionId", "s1")))))
                            "LOAD" -> onLoad({ ns, m -> send(ns, m) }, j.getInt("requestId"))
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
        return server
    }

    private fun status(rid: Int, state: String, time: Double) = JSONObject().put("type", "MEDIA_STATUS").put("requestId", rid)
        .put("status", JSONArray().put(JSONObject().put("mediaSessionId", 1).put("playerState", state).put("currentTime", time)))

    private fun client(server: SSLServerSocket, timeoutMs: Long) = CastClient(
        "127.0.0.1", server.localPort, { h, p -> Socket(h, p) }, {}, {}, loadTimeoutMs = timeoutMs,
    )

    private fun load(c: CastClient) =
        c.load("http://x/v.m3u8", "application/x-mpegURL", "t", 0.0, live = false, hlsFmp4 = false, headers = emptyMap())

    @Test
    fun loadingForeverWithoutProgressIsAFailure() {
        val server = fakeCast { send, rid -> send(CastNs.MEDIA, status(rid, "BUFFERING", 0.0)) }
        try {
            val c = client(server, 1500).also { it.connect() }
            val t0 = System.currentTimeMillis()
            val msg = try { load(c); "" } catch (e: CastException) { e.message.orEmpty() }
            assertTrue(msg, msg.contains("no terminó de cargar"))
            assertTrue(System.currentTimeMillis() - t0 < 6000)
        } finally { server.close() }
    }

    @Test
    fun bufferingThatIsAdvancingCountsAsPlaying() {
        val server = fakeCast { send, rid -> send(CastNs.MEDIA, status(rid, "BUFFERING", 5.0)) }
        try {
            val c = client(server, 1500).also { it.connect() }
            load(c) // no lanza
            assertEquals("BUFFERING", c.lastStatus.playerState)
        } finally { server.close() }
    }

    @Test
    fun abortCheckCancelsTheWaitEarly() {
        val server = fakeCast { send, rid -> send(CastNs.MEDIA, status(rid, "IDLE", 0.0)) }
        try {
            val c = client(server, 30_000).also { it.connect() }
            c.setAbortCheck { elapsed -> if (elapsed > 1200) "la tele no llega al móvil" else null }
            val t0 = System.currentTimeMillis()
            val msg = try { load(c); "" } catch (e: CastException) { e.message.orEmpty() }
            assertEquals("la tele no llega al móvil", msg)
            assertTrue("debía cortar pronto", System.currentTimeMillis() - t0 < 8000)
        } finally { server.close() }
    }
}
