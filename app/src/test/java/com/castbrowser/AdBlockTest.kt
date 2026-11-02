package com.castbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockTest {
    @Test
    fun blocksKnownAdHostsAndTheirSubdomains() {
        assertTrue(AdBlock.isAdHost("doubleclick.net"))
        assertTrue(AdBlock.isAdHost("ad.DoubleClick.net"))
        assertTrue(AdBlock.isAdHost("pagead2.googlesyndication.com"))
        assertTrue(AdBlock.isAdHost("a.b.exoclick.com"))
        assertTrue(AdBlock.isAdHost("popads.net."))
    }

    @Test
    fun doesNotBlockLookalikesOrNormalSites() {
        assertFalse(AdBlock.isAdHost("notdoubleclick.net"))
        assertFalse(AdBlock.isAdHost("doubleclick.net.example.com"))
        assertFalse(AdBlock.isAdHost("google.com"))
        assertFalse(AdBlock.isAdHost("muc002.myturn1.top"))
        assertFalse(AdBlock.isAdHost("commondatastorage.googleapis.com"))
        assertFalse(AdBlock.isAdHost(null))
        assertFalse(AdBlock.isAdHost(""))
    }

    @Test
    fun registrableDomain() {
        assertEquals("example.com", AdBlock.registrableDomain("www.example.com"))
        assertEquals("example.com", AdBlock.registrableDomain("a.b.example.com"))
        assertEquals("example.co.uk", AdBlock.registrableDomain("video.example.co.uk"))
        assertEquals("example.com.br", AdBlock.registrableDomain("www.example.com.br"))
        assertEquals("example.com", AdBlock.registrableDomain("example.com"))
        assertEquals("localhost", AdBlock.registrableDomain("localhost"))
        assertEquals("192.168.1.37", AdBlock.registrableDomain("192.168.1.37"))
    }

    @Test
    fun crossSiteDetection() {
        assertFalse(AdBlock.isCrossSite("www.site.com", "player.site.com"))
        assertFalse(AdBlock.isCrossSite("site.com", "site.com"))
        assertTrue(AdBlock.isCrossSite("site.com", "casino-ads.top"))
        assertTrue(AdBlock.isCrossSite("video.site.co.uk", "other.co.uk"))
        // Sin página de origen no se puede juzgar: se deja pasar.
        assertFalse(AdBlock.isCrossSite("", "casino-ads.top"))
        assertFalse(AdBlock.isCrossSite(null, "x.com"))
    }
}
