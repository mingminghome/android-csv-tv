package com.mmhw.csvtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewInputPolicyTest {

    @Test
    fun centerOnUrlBarIsImmediate() {
        assertEquals(
            WebViewInputPolicy.CenterAction.OPEN_URL_BAR,
            WebViewInputPolicy.centerAction(
                urlBarFocused = true,
                toolbarShowing = true,
                toolbarItemFocused = true,
                inFullscreen = false
            )
        )
    }

    @Test
    fun centerOnFocusedCloseIsImmediate() {
        assertEquals(
            WebViewInputPolicy.CenterAction.ACTIVATE_TOOLBAR,
            WebViewInputPolicy.centerAction(
                urlBarFocused = false,
                toolbarShowing = true,
                toolbarItemFocused = true,
                inFullscreen = false
            )
        )
    }

    @Test
    fun pageClickWaitsForDoubleClickWindow() {
        assertEquals(
            WebViewInputPolicy.CenterAction.DEFER_PAGE_OR_FULLSCREEN,
            WebViewInputPolicy.centerAction(
                urlBarFocused = false,
                toolbarShowing = false,
                toolbarItemFocused = false,
                inFullscreen = false
            )
        )
    }

    @Test
    fun fullscreenIgnoresToolbarFocus() {
        assertEquals(
            WebViewInputPolicy.CenterAction.DEFER_PAGE_OR_FULLSCREEN,
            WebViewInputPolicy.centerAction(
                urlBarFocused = false,
                toolbarShowing = true,
                toolbarItemFocused = true,
                inFullscreen = true
            )
        )
        assertFalse(
            WebViewInputPolicy.canActivateToolbarItem(
                inFullscreen = true,
                toolbarShowing = true,
                hasFocusedItem = true
            )
        )
    }

    @Test
    fun toolbarBandIsHostViewPixels() {
        assertTrue(
            WebViewInputPolicy.isPointerOverToolbar(
                pointerY = 20f,
                toolbarHeightPx = 56f,
                toolbarShowing = true,
                inFullscreen = false
            )
        )
        assertFalse(
            WebViewInputPolicy.isPointerOverToolbar(
                pointerY = 80f,
                toolbarHeightPx = 56f,
                toolbarShowing = true,
                inFullscreen = false
            )
        )
        assertFalse(
            WebViewInputPolicy.isPointerOverToolbar(
                pointerY = 20f,
                toolbarHeightPx = 56f,
                toolbarShowing = true,
                inFullscreen = true
            )
        )
    }

    @Test
    fun toolbarClickGoesToContainer() {
        assertEquals(
            WebViewInputPolicy.ClickDispatch.CONTAINER,
            WebViewInputPolicy.clickDispatch(overToolbar = true, containerReady = true)
        )
        assertEquals(
            WebViewInputPolicy.ClickDispatch.POINTER_HOST,
            WebViewInputPolicy.clickDispatch(overToolbar = false, containerReady = true)
        )
    }

    @Test
    fun challengeSkipsJsClickAndIme() {
        assertFalse(WebViewInputPolicy.shouldInjectJsClick(inFullscreen = false, onChallengePage = true))
        assertTrue(WebViewInputPolicy.shouldInjectJsClick(inFullscreen = false, onChallengePage = false))
        assertFalse(WebViewInputPolicy.shouldInjectJsClick(inFullscreen = true, onChallengePage = false))
        assertFalse(WebViewInputPolicy.shouldOfferIme(onChallengePage = true))
        assertTrue(WebViewInputPolicy.shouldOfferIme(onChallengePage = false))
    }

    @Test
    fun clampKeepsLastPixelForHitTesting() {
        val (x, y) = WebViewInputPolicy.clampToHost(2000f, -10f, 1280, 720)
        assertEquals(1279f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
    }

    @Test
    fun overlayAlignsHotspotToHost() {
        val (x, y) = WebViewInputPolicy.overlayTranslation(
            pointerX = 100f,
            pointerY = 50f,
            pointerWidth = 36,
            pointerHeight = 36,
            hostScreenX = 10,
            hostScreenY = 20,
            overlayScreenX = 0,
            overlayScreenY = 0
        )
        assertEquals(100f + 10f - 18f, x, 0.01f)
        assertEquals(50f + 20f - 18f, y, 0.01f)
    }

    @Test
    fun pointerSpeedAcceleratesAfterHold() {
        assertEquals(20f, WebViewInputPolicy.pointerSpeed(20f, sameKeyHeld = false, heldMs = 2000), 0.01f)
        assertEquals(20f, WebViewInputPolicy.pointerSpeed(20f, sameKeyHeld = true, heldMs = 200), 0.01f)
        val later = WebViewInputPolicy.pointerSpeed(20f, sameKeyHeld = true, heldMs = 1800)
        assertTrue(later > 20f)
        assertEquals(80f, WebViewInputPolicy.pointerSpeed(20f, sameKeyHeld = true, heldMs = 10_000), 0.01f)
    }

    @Test
    fun toolbarShowHideBands() {
        assertEquals(true, WebViewInputPolicy.toolbarVisibilityForPointerY(10f, 1f))
        assertNull(WebViewInputPolicy.toolbarVisibilityForPointerY(40f, 1f))
        assertEquals(false, WebViewInputPolicy.toolbarVisibilityForPointerY(80f, 1f))
    }

    @Test
    fun dpadDeltaMovesInKeyDirection() {
        assertEquals(0f to -20f, WebViewInputPolicy.dpadDelta(WebViewInputPolicy.DpadDir.UP, 20f))
        assertEquals(20f to 0f, WebViewInputPolicy.dpadDelta(WebViewInputPolicy.DpadDir.RIGHT, 20f))
    }

    @Test
    fun edgePanWhenParkedAtFarEdge() {
        val (dx, dy) = WebViewInputPolicy.edgePan(
            deltaX = 8f,
            deltaY = 0f,
            pointerX = 1279f,
            pointerY = 360f,
            hostWidth = 1280,
            hostHeight = 720,
            thresholdPx = 40f,
            step = 20
        )
        assertEquals(20, dx)
        assertEquals(0, dy)
    }
}
