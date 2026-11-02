package com.mmhw.csvtv.receiver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteRingTest {
    @Test
    fun wrapsAndKeepsNewest() {
        val ring = ByteRing(8)
        ring.write("01234".toByteArray(), 0, 5)
        ring.write("56789".toByteArray(), 0, 5)
        assertEquals(8, ring.size())
        assertEquals("23456789", String(ring.snapshot()))
    }

    @Test
    fun oversizedWriteKeepsTail() {
        val ring = ByteRing(4)
        ring.write("abcdefghij".toByteArray(), 0, 10)
        assertArrayEquals("ghij".toByteArray(), ring.snapshot())
    }

    @Test
    fun snapshotTsAlignsToSync() {
        val ring = ByteRing(188 * 4)
        val junk = byteArrayOf(1, 2, 3)
        val pkt = ByteArray(188) { 0x11 }
        pkt[0] = 0x47
        ring.write(junk, 0, junk.size)
        ring.write(pkt, 0, pkt.size)
        ring.write(pkt, 0, pkt.size)
        val snap = ring.snapshotTs()
        assertEquals(0, snap.size % 188)
        assertEquals(0x47, snap[0].toInt() and 0xff)
        assertEquals(0x47, snap[188].toInt() and 0xff)
    }
}
