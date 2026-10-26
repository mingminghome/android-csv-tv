package com.mmhw.csvtv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewDisplayPolicyTest {

    @Test
    fun desktopViewport_usesMinWidthWithoutLockingScale() {
        val js = WebViewDisplayPolicy.desktopViewportJs()
        assertTrue(js.contains("width=' + layoutWidth"))
        assertTrue(js.contains("Math.max(${WebViewDisplayPolicy.DESKTOP_MIN_LAYOUT_WIDTH}"))
        assertTrue(js.contains("user-scalable=yes"))
        assertTrue(js.contains("minimum-scale=0.25"))
        assertFalse(js.contains("initial-scale=1"))
        assertTrue(js.contains("overflow-x"))
        assertTrue(js.contains("overflow-y"))
    }

    @Test
    fun pageScroll_coversHorizontalAndDocumentFallback() {
        val js = WebViewDisplayPolicy.pageScrollJs(10, 20, 30, 40)
        assertTrue(js.contains("var ptX = 10"))
        assertTrue(js.contains("var ptY = 20"))
        assertTrue(js.contains("var dx = 30"))
        assertTrue(js.contains("var dy = 40"))
        assertTrue(js.contains("overflowX"))
        assertTrue(js.contains("overflowY"))
        assertTrue(js.contains("window.scrollBy(dx, dy)"))
        assertTrue(js.contains("scrollingElement"))
        assertTrue(js.contains("elementFromPoint"))
    }

    @Test
    fun contentSize_usesDocumentAndBody() {
        val js = WebViewDisplayPolicy.contentSizeJs()
        assertTrue(js.contains("scrollWidth"))
        assertTrue(js.contains("scrollHeight"))
        assertTrue(js.contains("document.documentElement"))
        assertTrue(js.contains("document.body"))
    }
}
