package com.castbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDetectorTest {
    @Test
    fun contentDecidesWhatAnAddressReturns() {
        assertEquals("hls", MediaDetector.kindFromContent("text/plain", "#EXTM3U\n#EXT-X-VERSION:3"))
        assertEquals("hls", MediaDetector.kindFromContent(null, "﻿\n#EXTM3U"))
        assertEquals("hls", MediaDetector.kindFromContent("application/vnd.apple.mpegurl", ""))
        assertEquals("dash", MediaDetector.kindFromContent("application/xml", "<?xml version=\"1.0\"?>\n<MPD xmlns=\"x\">"))
        assertEquals("dash", MediaDetector.kindFromContent(null, "<MPD type=\"dynamic\">"))
        assertEquals("dash", MediaDetector.kindFromContent("application/dash+xml", ""))
        assertEquals("file", MediaDetector.kindFromContent("video/mp4", "\u0000\u0000"))
        assertNull(MediaDetector.kindFromContent("text/html", "<!doctype html><html>"))
        assertNull(MediaDetector.kindFromContent("application/json", "{\"a\":1}"))
    }

    @Test
    fun playlistsAreGuessedNextToASegment() {
        val g = MediaDetector.siblingPlaylists("https://cdn.x/live/abc/1847.ts?t=1&k=2")
        assertTrue(g.contains("https://cdn.x/live/abc/playlist.m3u8?t=1&k=2"))
        assertTrue(g.contains("https://cdn.x/live/abc/index.m3u8"))
        assertTrue(g.contains("https://cdn.x/live/master.m3u8"))        // carpeta superior
        assertEquals(g.size, g.toSet().size)
        // Sin carpeta superior útil no se inventan direcciones rotas.
        val edge = MediaDetector.siblingPlaylists("https://cdn.x/1.ts")
        assertTrue(edge.all { it.startsWith("https://cdn.x/") })
    }

    @Test
    fun onlyMediaLookingRequestsAreWorthShowing() {
        assertTrue(MediaDetector.isInteresting("https://cdn.x/live/abc/12.ts"))
        assertTrue(MediaDetector.isInteresting("https://cdn.x/v/index.m3u8?t=1"))
        assertTrue(MediaDetector.isInteresting("https://cdn.x/stream/abc"))
        assertFalse(MediaDetector.isInteresting("https://cdn.x/app.js"))
        assertFalse(MediaDetector.isInteresting("https://cdn.x/img/logo.png?video=1"))
        assertFalse(MediaDetector.isInteresting("https://cdn.x/api/user"))
        assertFalse(MediaDetector.isInteresting("blob:https://x/123"))
        assertTrue(MediaDetector.looksLikeSegment("https://cdn.x/a/12.ts?x=1"))
        assertFalse(MediaDetector.looksLikeSegment("https://cdn.x/a/index.m3u8"))
    }

    @Test
    fun captureLogKeepsRecentRequestsAndKnownStreams() {
        val log = CaptureLog()
        log.noteRequest("https://cdn.x/app.js", emptyMap())
        log.noteRequest("https://cdn.x/live/1.ts", mapOf("Referer" to "https://site/"))
        log.noteRequest("https://cdn.x/live/1.ts", emptyMap())      // repetida
        log.noteRequest("https://cdn.x/live/2.ts", emptyMap())
        assertEquals(listOf("https://cdn.x/live/2.ts", "https://cdn.x/live/1.ts"), log.recent().map { it.first })

        assertTrue(log.candidates(null).isEmpty())
        log.addKnown("https://site/api/getstream?id=1", mapOf("Referer" to "https://site/"), "hls")
        log.addKnown("https://site/api/getstream?id=1", emptyMap(), "hls")   // repetida
        val c = log.candidates(null)
        assertEquals(1, c.size)
        assertEquals("hls", c[0].kind)

        log.clear()
        assertTrue(log.recent().isEmpty() && log.candidates(null).isEmpty())
    }
}
