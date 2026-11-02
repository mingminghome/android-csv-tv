package com.mmhw.csvtv.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastCryptoPolicyTest {
    @Test
    fun turningOnWithoutPassphraseNeedsDialog() {
        assertTrue(CastCryptoPolicy.needsPassphraseDialog(currentlyOn = false, savedPassphrase = ""))
        assertTrue(CastCryptoPolicy.needsPassphraseDialog(currentlyOn = false, savedPassphrase = "   "))
    }

    @Test
    fun turningOnWithSavedPassphraseSkipsDialog() {
        assertFalse(
            CastCryptoPolicy.needsPassphraseDialog(currentlyOn = false, savedPassphrase = "secret")
        )
    }

    @Test
    fun turningOffNeverNeedsDialog() {
        assertFalse(CastCryptoPolicy.needsPassphraseDialog(currentlyOn = true, savedPassphrase = ""))
        assertFalse(
            CastCryptoPolicy.needsPassphraseDialog(currentlyOn = true, savedPassphrase = "secret")
        )
    }

    @Test
    fun encryptionOnlyPlaysToCastify() {
        assertTrue(CastCryptoPolicy.playAllowed(encryptionOn = false, targetIsCastify = false))
        assertTrue(CastCryptoPolicy.playAllowed(encryptionOn = false, targetIsCastify = true))
        assertTrue(CastCryptoPolicy.playAllowed(encryptionOn = true, targetIsCastify = true))
        assertFalse(CastCryptoPolicy.playAllowed(encryptionOn = true, targetIsCastify = false))
    }
}
