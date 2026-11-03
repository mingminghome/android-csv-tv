package com.mmhw.csvtv.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastOverflowTest {
    @Test
    fun autoRotateOnlyOnPhoneReceive() {
        assertTrue(CastOverflow.showAutoRotateItem(receiveHost = true, handheld = true))
        assertFalse(CastOverflow.showAutoRotateItem(receiveHost = true, handheld = false))
        assertFalse(CastOverflow.showAutoRotateItem(receiveHost = false, handheld = true))
        assertFalse(CastOverflow.showAutoRotateItem(receiveHost = false, handheld = false))
    }
}
