package com.castbrowser.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class PushStreamTest {
    private fun drain(r: PushStream.Reader, chunk: Int = 4): String {
        val buf = ByteArray(chunk)
        val sb = StringBuilder()
        while (true) {
            val n = r.read(buf, 0, buf.size, 200)
            if (n < 0) break
            if (n == 0) break
            sb.append(String(buf, 0, n))
        }
        return sb.toString()
    }

    @Test
    fun readsInOrderAndEndsAfterFinish() {
        val s = PushStream()
        s.write("abc".toByteArray(), 0, 3)
        s.write("defg".toByteArray(), 1, 2) // "ef"
        s.finish()
        assertEquals("abcef", drain(s.reader()))
    }

    @Test
    fun readReturnsZeroWhenNothingArrives() {
        assertEquals(0, PushStream().reader().read(ByteArray(8), 0, 8, 60))
    }

    @Test
    fun writerWaitsWhenTooMuchIsUnreadAndResumes() {
        val s = PushStream(unreadLimit = 10, keepBehind = 4)
        s.write(ByteArray(8), 0, 8)
        var wrote = false
        val t = Thread { s.write(ByteArray(8), 0, 8); wrote = true }
        t.start()
        Thread.sleep(300)
        assertFalse("debía esperar con tanto sin leer", wrote)
        s.reader().read(ByteArray(8), 0, 8, 200) // la tele consume
        t.join(3000)
        assertTrue(wrote)
    }

    @Test
    fun abortMakesTheWriterFailAndEndsReaders() {
        val s = PushStream()
        s.abort()
        try { s.write(ByteArray(1), 0, 1); fail() } catch (_: IOException) {}
        assertEquals(-1, s.reader().read(ByteArray(4), 0, 4, 50))
    }

    @Test
    fun aSecondReaderCanReplayFromAnyRetainedPosition() {
        val s = PushStream()
        listOf("0123", "4567", "89ab", "cdef").forEach { s.write(it.toByteArray(), 0, 4) }
        s.finish()
        val first = s.reader()
        assertEquals("0123456789", String(ByteArray(10).also { first.read(it, 0, 10, 100) }))
        // El reproductor se reconecta pidiendo desde la posición 6 (lo ya leído sigue disponible).
        assertEquals("6789abcdef", drain(s.reader(6), 3))
        // Y el primero puede seguir donde iba.
        assertEquals("abcdef", drain(first, 5))
    }

    @Test
    fun oldDataIsDroppedAndAReaderJumpsToTheOldestRetained() {
        val s = PushStream(unreadLimit = 1000, keepBehind = 4)
        listOf("0123", "4567", "89ab", "cdef").forEach { s.write(it.toByteArray(), 0, 4) }
        s.finish()
        val r = s.reader()
        r.read(ByteArray(16), 0, 16, 100)   // lee todo: se descarta lo anterior a (16 - 4)
        val late = s.reader(0)
        val got = drain(late, 8)
        assertTrue(got, got.endsWith("cdef") && got.length < 16)
    }

    private fun body(resp: ByteArray): Pair<String, ByteArray> {
        val text = String(resp, Charsets.ISO_8859_1)
        val i = text.indexOf("\r\n\r\n")
        return text.substring(0, i) to resp.copyOfRange(i + 4, resp.size)
    }

    @Test
    fun pushedBytesReachThePlayerIntact() {
        PushStreams.closeAll()
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val live = Thread { PushStreams.serveLive("t1", out, firstDataTimeoutMs = 5000, idleTimeoutMs = 5000) }
        live.start()
        val pusher = Thread { PushStreams.servePush("t1", ByteArrayInputStream(payload)) }
        pusher.start()
        pusher.join(5000)
        live.join(8000)
        val (head, data) = body(out.toByteArray())
        assertTrue(head, head.startsWith("HTTP/1.1 200 OK") && head.contains("video/mp2t"))
        assertArrayEquals(payload, data)
    }

    @Test
    fun rangeRequestsGet206FromThatByte() {
        PushStreams.closeAll()
        val payload = ByteArray(1000) { (it % 200).toByte() }
        PushStreams.servePush("r1", ByteArrayInputStream(payload))
        val out = ByteArrayOutputStream()
        PushStreams.serveLive("r1", out, start = 400, firstDataTimeoutMs = 2000, idleTimeoutMs = 2000)
        val (head, data) = body(out.toByteArray())
        assertTrue(head, head.startsWith("HTTP/1.1 206") && head.contains("Content-Range: bytes 400-*/*"))
        assertArrayEquals(payload.copyOfRange(400, 1000), data)
    }

    @Test
    fun aPlayerThatLeavesDoesNotKillTheStream() {
        PushStreams.closeAll()
        var calls = 0
        val leavingPlayer = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) { if (calls++ >= 1) throw IOException("cerrado") }
        }
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val pusher = Thread { PushStreams.servePush("t2", ByteArrayInputStream(payload)) }
        pusher.start()
        PushStreams.serveLive("t2", leavingPlayer, firstDataTimeoutMs = 5000)   // se va tras la cabecera
        pusher.join(5000)
        assertFalse("el que empuja debía poder terminar", pusher.isAlive)
        // El reproductor vuelve y lo recibe todo.
        val out = ByteArrayOutputStream()
        PushStreams.serveLive("t2", out, firstDataTimeoutMs = 2000, idleTimeoutMs = 2000)
        assertArrayEquals(payload, body(out.toByteArray()).second)
    }

    @Test
    fun playerGivesUpIfNothingIsPushed() {
        PushStreams.closeAll()
        val out = ByteArrayOutputStream()
        val t0 = System.currentTimeMillis()
        PushStreams.serveLive("nada", out, firstDataTimeoutMs = 600)
        assertTrue(System.currentTimeMillis() - t0 < 5000)
        assertEquals(0, body(out.toByteArray()).second.size)
    }

    @Test
    fun eventsAreRecordedForTheDiagnostic() {
        PushStreams.closeAll()
        PushStreams.servePush("e1", ByteArrayInputStream(ByteArray(10)))
        val text = PushStreams.eventsText()
        assertTrue(text, text.contains("el móvil empieza a empujar") && text.contains("terminó de empujar"))
    }
}
