package com.futmondo.core

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryTest {
    @Test fun roundTripsNewestFirstAndCapsSize() {
        var l = emptyList<HistoryEntry>()
        for (i in 1..(History.MAX + 5)) l = History.add(l, HistoryEntry(i.toLong(), HistoryKind.ANALISIS, "n$i"))
        assertEquals(History.MAX, l.size)
        assertEquals("n${History.MAX + 5}", l.first().summary)
        assertEquals(l, History.fromJson(History.toJson(l)))
    }

    @Test fun toleratesGarbageAndFormatsInSpanish() {
        assertTrue(History.fromJson("no es json").isEmpty())
        assertEquals("El historial está vacío.", History.format(emptyList()))
        val txt = History.format(listOf(HistoryEntry(0, HistoryKind.VENTA, "x")), TimeZone.getTimeZone("UTC"))
        assertEquals("01/01/1970 00:00 · Puesta a la venta\nx", txt)
    }
}
