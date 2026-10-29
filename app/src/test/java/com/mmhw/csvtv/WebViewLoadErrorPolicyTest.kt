package com.mmhw.csvtv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewLoadErrorPolicyTest {

    @Test
    fun subresourceNeverExitsOrToasts() {
        assertFalse(WebViewLoadErrorPolicy.shouldAnnounce(isMainFrame = false))
        assertFalse(
            WebViewLoadErrorPolicy.shouldExitToBrowse(
                isMainFrame = false,
                initialLoadCompleted = false
            )
        )
    }

    @Test
    fun firstMainFrameFailureLeavesBrowse() {
        assertTrue(WebViewLoadErrorPolicy.shouldAnnounce(isMainFrame = true))
        assertTrue(
            WebViewLoadErrorPolicy.shouldExitToBrowse(
                isMainFrame = true,
                initialLoadCompleted = false
            )
        )
    }

    @Test
    fun laterMainFrameFailureStaysOnPage() {
        assertTrue(WebViewLoadErrorPolicy.shouldAnnounce(isMainFrame = true))
        assertFalse(
            WebViewLoadErrorPolicy.shouldExitToBrowse(
                isMainFrame = true,
                initialLoadCompleted = true
            )
        )
    }
}
