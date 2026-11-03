package com.castbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsRelayTest {
    private fun fetcher(map: Map<String, String>): (String) -> ByteArray = { u ->
        (map[u] ?: throw java.io.IOException("HTTP 404 $u")).toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun picksBestVariantUnderCap() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=700000
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            mid/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=12000000
            /hi/index.m3u8
        """.trimIndent()
        assertEquals("https://cdn.x/live/mid/index.m3u8", Hls.pickVariant(master, "https://cdn.x/live/master.m3u8"))
        val onlyHeavy = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=20000000\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=9000000\nb.m3u8\n"
        assertEquals("https://cdn.x/b.m3u8", Hls.pickVariant(onlyHeavy, "https://cdn.x/m.m3u8"))
    }

    @Test
    fun listsQualitiesBestFirst() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhd.m3u8\n"
        val v = Hls.variants(master, "https://cdn.x/a/m.m3u8")
        assertEquals(listOf("1920x1080 5.0 Mbps", "640x360 0.8 Mbps"), v.map { it.label })
        assertEquals("https://cdn.x/a/hd.m3u8", v[0].url)
        assertEquals("https://cdn.x/a/hd.m3u8", Hls.pickVariant(master, "https://cdn.x/a/m.m3u8"))
        // Lista simple: no hay calidades.
        assertTrue(Hls.variants("#EXTM3U\n#EXTINF:2,\na.ts\n", "https://x/").isEmpty())
    }

    @Test
    fun vodIsConcatenatedFromTheStart() {
        val base = "https://cdn.x/v/"
        val files = mapOf(
            base + "master.m3u8" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nmedia.m3u8\n",
            base + "media.m3u8" to "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:4,\ns0.ts\n#EXTINF:4,\ns1.ts\n#EXTINF:4,\ns2.ts\n#EXT-X-ENDLIST\n",
            base + "s0.ts" to "AAA", base + "s1.ts" to "BBB", base + "s2.ts" to "CCC",
        )
        val conv = HlsToTs(fetcher(files), sleep = {})
        val out = ByteArrayOutputStream()
        conv.stream(conv.open(base + "master.m3u8"), out)
        assertEquals("AAABBBCCC", out.toString(Charsets.ISO_8859_1))
    }

    @Test
    fun liveStartsNearTheEdgeAndFollowsNewSegments() {
        val base = "https://cdn.x/l/"
        var polls = 0
        val fetch: (String) -> ByteArray = { u ->
            val text = when {
                u == base + "live.m3u8" -> {
                    polls++
                    // consultas 1-2: 10..14 · 3: 11..15 · 4 en adelante: 12..16 y termina
                    val first = when (polls) { 1, 2 -> 10; 3 -> 11; else -> 12 }
                    val lastSeq = first + 4
                    val sb = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$first\n")
                    for (n in first..lastSeq) sb.append("#EXTINF:2,\ns$n.ts\n")
                    if (polls >= 4) sb.append("#EXT-X-ENDLIST\n")
                    sb.toString()
                }
                u.startsWith(base + "s") -> "<" + u.substringAfterLast("/s").substringBefore(".") + ">"
                else -> throw java.io.IOException("HTTP 404")
            }
            text.toByteArray()
        }
        val conv = HlsToTs(fetch, sleep = {})
        val out = ByteArrayOutputStream()
        conv.stream(conv.open(base + "live.m3u8"), out)
        // Empieza en los 3 últimos del primer listado (12, 13, 14) y sigue con 15 y 16.
        assertEquals("<12><13><14><15><16>", out.toString())
    }

    @Test
    fun aes128SegmentsAreDecrypted() {
        val base = "https://cdn.x/e/"
        val key = ByteArray(16) { (it + 1).toByte() }
        fun enc(seq: Long, plain: String): ByteArray {
            val iv = ByteArray(16).also { b -> var v = seq; for (i in 15 downTo 8) { b[i] = (v and 0xFF).toByte(); v = v ushr 8 } }
            return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(plain.toByteArray())
            }
        }
        val bytes = mapOf(
            base + "k.bin" to key,
            base + "s5.ts" to enc(5, "hola-cinco"),
            base + "s6.ts" to enc(6, "hola-seis"),
        )
        val fetch: (String) -> ByteArray = { u ->
            if (u == base + "m.m3u8") (
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n" +
                    "#EXTINF:2,\ns5.ts\n#EXTINF:2,\ns6.ts\n#EXT-X-ENDLIST\n").toByteArray()
            else bytes[u] ?: throw java.io.IOException("HTTP 404")
        }
        val conv = HlsToTs(fetch, sleep = {})
        val out = ByteArrayOutputStream()
        conv.stream(conv.open(base + "m.m3u8"), out)
        assertEquals("hola-cincohola-seis", out.toString())
    }

    @Test
    fun unsupportedStreamsAreRejected() {
        fun reason(text: String): String? = Hls.parseMedia(text, "https://x/y/m.m3u8").unsupported
        assertEquals("segmentos fMP4", reason("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\na.m4s\n"))
        assertEquals("segmentos fMP4", reason("#EXTM3U\n#EXTINF:2,\nvideo-1.m4s?x=1\n"))
        assertEquals("cifrado SAMPLE-AES", reason("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:2,\na.ts\n"))
        assertNull(reason("#EXTM3U\n#EXTINF:2,\na.ts\n"))

        val conv = HlsToTs(fetcher(mapOf("https://x/m.m3u8" to "#EXTM3U\n#EXT-X-MAP:URI=\"i.mp4\"\n#EXTINF:2,\na.m4s\n")))
        val msg = try { conv.open("https://x/m.m3u8"); "" } catch (e: HlsUnsupported) { e.message.orEmpty() }
        assertTrue(msg, msg.contains("fMP4"))
        val notHls = try { HlsToTs(fetcher(mapOf("https://x/n" to "<html>"))).open("https://x/n"); "" }
        catch (e: HlsUnsupported) { e.message.orEmpty() }
        assertTrue(notHls, notHls.contains("no devuelve una lista HLS"))
    }

    @Test
    fun proxyServesTheRelayedStream() {
        val tv = FakeHttp { _, path, _, _ ->
            when (path) {
                "/live/index.m3u8" -> Triple(200, "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2,\ns0.ts\n#EXTINF:2,\ns1.ts\n#EXT-X-ENDLIST\n")
                "/live/s0.ts" -> Triple(200, "video/mp2t", "AAAA")
                "/live/s1.ts" -> Triple(200, "video/mp2t", "BBBB")
                else -> Triple(404, "text/plain", "")
            }
        }
        val proxy = MediaProxy("127.0.0.1")
        try {
            val playlist = tv.base + "/live/index.m3u8"
            proxy.canRelay(playlist, emptyMap())
            val relay = proxy.relayUrl(playlist, emptyMap(), "video/mpeg")
            assertTrue(relay, relay.endsWith(".ts") && relay.contains("/ts/"))

            val get = URL(relay).openConnection() as HttpURLConnection
            assertEquals(200, get.responseCode)
            assertEquals("video/mpeg", get.contentType)
            assertEquals("Streaming", get.getHeaderField("transferMode.dlna.org"))
            assertEquals("AAAABBBB", get.inputStream.readBytes().toString(Charsets.UTF_8))

            val head = URL(relay).openConnection() as HttpURLConnection
            head.requestMethod = "HEAD"
            assertEquals(200, head.responseCode)
            assertEquals(0, head.inputStream.readBytes().size)

            // Una lista que no existe: 502 en vez de colgar la conexión.
            val bad = proxy.relayUrl(tv.base + "/nada.m3u8", emptyMap(), "video/mpeg")
            assertEquals(502, (URL(bad).openConnection() as HttpURLConnection).responseCode)
            val missing = (URL(proxy.baseUrl + "/ts/t99.ts").openConnection() as HttpURLConnection).responseCode
            assertEquals(404, missing)
        } finally {
            proxy.close(); tv.close()
        }
    }
}
