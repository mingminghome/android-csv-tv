package com.mmhw.csvtv.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverIdTest {
    @Test
    fun shortIdUsesLastFourHex() {
        assertEquals("C3D4", ReceiverId.shortId("a1b2c3d4-e5f6-7890-abcd-ef012345c3d4"))
        assertEquals("C3D4", ReceiverId.shortId("uuid:A1B2C3D4-E5F6-7890-ABCD-EF012345C3D4"))
        assertEquals("7C2A", ReceiverId.shortId("7c2a"))
        assertEquals("0007", ReceiverId.shortId("7"))
        assertEquals("0000", ReceiverId.shortId(""))
    }

    @Test
    fun listLabelAddsIdWhenNameHasNone() {
        assertEquals("TV, Receive · C3D4", ReceiverId.listLabel("TV, Receive", "uuid:....c3d4"))
        assertEquals("TV, Receive · C3D4", ReceiverId.listLabel("TV, Receive · C3D4", "....c3d4"))
    }
}
