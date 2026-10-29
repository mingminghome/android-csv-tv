package com.mmhw.csvtv

import org.junit.Assert.assertEquals
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
        val js = WebViewDisplayPolicy.pageScrollJs(10f, 20f, 1280, 720, 30, 40)
        assertTrue(js.contains("var vx = 10"))
        assertTrue(js.contains("var vy = 20"))
        assertTrue(js.contains("var vw = 1280"))
        assertTrue(js.contains("var vh = 720"))
        assertTrue(js.contains("var dx = 30"))
        assertTrue(js.contains("var dy = 40"))
        assertTrue(js.contains("visualViewport"))
        assertTrue(js.contains("overflowX"))
        assertTrue(js.contains("overflowY"))
        assertTrue(js.contains("window.scrollBy(dx, dy)"))
        assertTrue(js.contains("scrollingElement"))
        assertTrue(js.contains("elementFromPoint"))
        assertTrue(js.contains("cssW - 1"))
    }

    @Test
    fun clickAtViewPoint_mapsThroughVisualViewport() {
        val js = WebViewDisplayPolicy.clickAtViewPointJs(1279f, 719f, 1280, 720)
        assertTrue(js.contains("visualViewport"))
        assertTrue(js.contains("elementFromPoint(ptX, ptY)"))
        assertTrue(js.contains("el.click()"))
        assertFalse(js.contains("devicePixelRatio"))
    }

    @Test
    fun viewToClient_originStaysAtOrigin() {
        assertEquals(0f, WebViewDisplayPolicy.viewToClient(0f, 1280, 1280f), 0.01f)
    }

    @Test
    fun viewToClient_farEdgeStaysInsideCssViewport() {
        // Overview-fitted desktop layout: view px == css px. Density is irrelevant.
        assertEquals(1279f, WebViewDisplayPolicy.viewToClient(1280f, 1280, 1280f), 0.01f)
        // Same view, visual CSS width 961 (density ~1.33). Far edge is 960, not 1280/1.33.
        assertEquals(960f, WebViewDisplayPolicy.viewToClient(1280f, 1280, 961f), 0.01f)
    }

    @Test
    fun viewToClient_midpointScalesWithCssWidth() {
        assertEquals(640f, WebViewDisplayPolicy.viewToClient(640f, 1280, 1280f), 0.01f)
        assertEquals(480.5f, WebViewDisplayPolicy.viewToClient(640f, 1280, 961f), 0.01f)
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
