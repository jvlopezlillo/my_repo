package com.castbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PushStreamerTest {
    private fun fetcher(map: Map<String, String>): (String) -> ByteArray = { u ->
        (map[u] ?: throw java.io.IOException("HTTP 404 $u")).toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun pushesTheConvertedStreamToTheTv() {
        val base = "https://cdn.x/v/"
        val files = mapOf(
            base + "m.m3u8" to "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:2,\ns0.ts\n#EXTINF:2,\ns1.ts\n#EXT-X-ENDLIST\n",
            base + "s0.ts" to "AAA", base + "s1.ts" to "BBB",
        )
        val tv = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val received = ByteArrayOutputStream()
        var header = ""
        val done = CountDownLatch(1)
        Thread {
            tv.accept().use { s ->
                val inp = s.getInputStream()
                val sb = StringBuilder()
                while (!sb.endsWith("\r\n\r\n")) sb.append(inp.read().toChar())
                header = sb.toString()
                inp.copyTo(received)
                done.countDown()
            }
        }.start()
        try {
            val conv = HlsToTs(fetcher(files), sleep = {})
            val session = conv.open(base + "m.m3u8")
            val st = PushStreamer({ Socket(InetAddress.getLoopbackAddress(), tv.localPort) }, "abc123", conv, session)
            st.start()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(header, header.startsWith("PUSH /push/abc123 HTTP/1.1"))
            assertEquals("AAABBB", received.toString())
            assertEquals(6L, st.bytesSent)
        } finally {
            tv.close()
        }
    }

    @Test
    fun stopClosesTheConnectionAndEndsThePush() {
        val base = "https://cdn.x/live/"
        // Un directo que nunca termina: siempre hay un trozo más.
        var n = 0
        val fetch: (String) -> ByteArray = { u ->
            if (u.endsWith("l.m3u8")) {
                n++
                val sb = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$n\n")
                for (i in n..n + 2) sb.append("#EXTINF:1,\ns$i.ts\n")
                sb.toString().toByteArray()
            } else "xxxx".toByteArray()
        }
        val tv = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val closedByPeer = CountDownLatch(1)
        Thread {
            tv.accept().use { s ->
                val inp = s.getInputStream()
                val buf = ByteArray(4096)
                while (inp.read(buf) >= 0) { /* consume */ }
                closedByPeer.countDown()
            }
        }.start()
        try {
            val conv = HlsToTs(fetch, sleep = { Thread.sleep(10) })
            val st = PushStreamer({ Socket(InetAddress.getLoopbackAddress(), tv.localPort) }, "x", conv, conv.open(base + "l.m3u8"))
            st.start()
            Thread.sleep(300)
            assertTrue(st.running)
            st.stop()
            assertTrue("la tele debía ver cerrarse la conexión", closedByPeer.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertFalse(st.running)
        } finally {
            tv.close()
        }
    }
}
