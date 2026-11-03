package com.mmhw.csvtv.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveChromePolicyTest {
    @Test
    fun photoVideoAndScreenCastHideOverlay() {
        assertTrue(
            ReceiveChromePolicy.autoHides(
                showingMedia = true,
                web = false,
                confirm = false,
                idle = false
            )
        )
    }

    @Test
    fun idleKeepsToolbar() {
        assertFalse(
            ReceiveChromePolicy.autoHides(
                showingMedia = false,
                web = false,
                confirm = false,
                idle = true
            )
        )
    }

    @Test
    fun webAndConfirmStayVisible() {
        assertFalse(
            ReceiveChromePolicy.autoHides(
                showingMedia = true,
                web = true,
                confirm = false,
                idle = false
            )
        )
        assertFalse(
            ReceiveChromePolicy.autoHides(
                showingMedia = true,
                web = false,
                confirm = true,
                idle = false
            )
        )
    }
}
