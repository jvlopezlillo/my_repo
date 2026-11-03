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
    @Test
    fun readsInOrderAndEndsAfterFinish() {
        val s = PushStream()
        s.write("abc".toByteArray(), 0, 3)
        s.write("defg".toByteArray(), 1, 2) // "ef"
        s.finish()
        val buf = ByteArray(4)
        val out = StringBuilder()
        while (true) {
            val n = s.read(buf, 100)
            if (n < 0) break
            out.append(String(buf, 0, n))
        }
        assertEquals("abcef", out.toString())
    }

    @Test
    fun readReturnsZeroWhenNothingArrives() {
        assertEquals(0, PushStream().read(ByteArray(8), 60))
    }

    @Test
    fun writerWaitsWhenTheBufferIsFullAndResumes() {
        val s = PushStream(capacity = 10)
        s.write(ByteArray(8), 0, 8)
        var wrote = false
        val t = Thread { s.write(ByteArray(8), 0, 8); wrote = true }
        t.start()
        Thread.sleep(300)
        assertFalse("debía esperar con el búfer lleno", wrote)
        s.read(ByteArray(8), 200) // la tele consume
        t.join(3000)
        assertTrue(wrote)
    }

    @Test
    fun abortMakesTheWriterFail() {
        val s = PushStream()
        s.abort()
        try { s.write(ByteArray(1), 0, 1); fail() } catch (_: IOException) {}
        assertEquals(-1, s.read(ByteArray(4), 50))
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
    fun playerGivesUpIfNothingIsPushed() {
        PushStreams.closeAll()
        val out = ByteArrayOutputStream()
        val t0 = System.currentTimeMillis()
        PushStreams.serveLive("nada", out, firstDataTimeoutMs = 600)
        assertTrue(System.currentTimeMillis() - t0 < 5000)
        assertEquals(0, body(out.toByteArray()).second.size)
    }

    @Test
    fun whenThePlayerClosesThePusherStops() {
        PushStreams.closeAll()
        var calls = 0
        val player = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) { if (calls++ >= 1) throw IOException("cerrado") }
        }
        val endless = object : InputStream() {
            override fun read(): Int = 7
            override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(5); return minOf(len, 1000) }
        }
        val live = Thread { PushStreams.serveLive("t2", player, firstDataTimeoutMs = 5000) }
        val pusher = Thread { PushStreams.servePush("t2", endless) }
        live.start(); pusher.start()
        pusher.join(5000)
        assertFalse("el que empuja debía parar al cerrarse el reproductor", pusher.isAlive)
        pusher.interrupt(); live.join(2000)
    }
}
