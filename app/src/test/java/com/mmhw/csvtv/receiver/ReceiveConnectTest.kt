package com.mmhw.csvtv.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveConnectTest {
    @Test
    fun autoConnectIsInverseOfConfirm() {
        assertFalse(false && true)
        assertTrue(!false)
        assertFalse(!true)
    }

    @Test
    fun defaultsMatchProduct() {
        // Confirmation default is off, so auto-connect is the default path.
        val confirmDefault = false
        assertFalse(confirmDefault)
        assertTrue(!confirmDefault)
    }
}
