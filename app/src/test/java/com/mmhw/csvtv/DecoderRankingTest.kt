package com.mmhw.csvtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderRankingTest {
    @Test
    fun hardwarePreferredOverSoftwareAndEmulator() {
        val hw = DecoderRanking.sortKey("c2.qti.avc.decoder", hardwareAccelerated = true, preferSoftware = false)
        val sw = DecoderRanking.sortKey("c2.android.avc.decoder", hardwareAccelerated = false, preferSoftware = false)
        val emu = DecoderRanking.sortKey("c2.goldfish.h264.decoder", hardwareAccelerated = true, preferSoftware = false)
        assertTrue(hw < sw)
        assertTrue(sw < emu)
    }

    @Test
    fun softwarePreferredRanksGoogleFirst() {
        val sw = DecoderRanking.sortKey("c2.android.avc.decoder", hardwareAccelerated = false, preferSoftware = true)
        val hw = DecoderRanking.sortKey("c2.qti.avc.decoder", hardwareAccelerated = true, preferSoftware = true)
        assertTrue(sw < hw)
    }

    @Test
    fun emulatorAndGoogleNameChecks() {
        assertTrue(DecoderRanking.isEmulatorCodec("OMX.google.goldfish.decoder"))
        assertTrue(DecoderRanking.isGoogleSoftware("c2.android.avc.decoder"))
        assertFalse(DecoderRanking.isEmulatorCodec("c2.qti.avc.decoder"))
    }
}
