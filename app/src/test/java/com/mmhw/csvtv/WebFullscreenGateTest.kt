package com.mmhw.csvtv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebFullscreenGateTest {

    private var now = 1_000_000L
    private lateinit var gate: WebFullscreenGate

    @Before
    fun setUp() {
        now = 1_000_000L
        gate = WebFullscreenGate(clockMs = { now })
    }

    @Test
    fun firstSiteRequest_isAccepted() {
        assertTrue(gate.shouldAcceptSiteFullscreenRequest())
    }

    @Test
    fun afterUserExit_siteCannotReopenUntilClick() {
        gate.onUserExitedFullscreen()
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())

        now += WebFullscreenGate.HIDE_COOLDOWN_MS + 1
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())

        gate.onUserPageClick()
        assertTrue(gate.shouldAcceptSiteFullscreenRequest())
    }

    @Test
    fun afterUserExit_explicitFullscreenButtonIsHonored() {
        gate.onUserExitedFullscreen()
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())

        gate.onUserRequestedFullscreen()
        assertTrue(gate.shouldAcceptSiteFullscreenRequest())
    }

    @Test
    fun afterAnyHide_briefCooldownBlocksInstantReopen() {
        gate.onFullscreenHidden()
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())

        now += WebFullscreenGate.HIDE_COOLDOWN_MS - 1
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())

        now += 2
        assertTrue(gate.shouldAcceptSiteFullscreenRequest())
    }

    @Test
    fun userTypedAddressClearsSuppress() {
        gate.onUserExitedFullscreen()
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())

        gate.onNavigatedToNewPage()
        assertTrue(gate.shouldAcceptSiteFullscreenRequest())
    }

    @Test
    fun automaticPlayerUrlUpdate_doesNotUnlock() {
        gate.onUserExitedFullscreen()
        // Query-only player hops are not a user address change.
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())
        now += WebFullscreenGate.HIDE_COOLDOWN_MS + 5_000
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())
    }

    @Test
    fun afterDismiss_alternatePlaylistStillBlockedUntilClick() {
        gate.onUserExitedFullscreen()
        // A different CDN playlist on the same page must not auto-reopen.
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())
        gate.onUserPageClick()
        assertTrue(gate.shouldAcceptSiteFullscreenRequest())
    }

    @Test
    fun explicitRequestConsumesOnlyOneSiteOpen() {
        gate.onUserExitedFullscreen()
        gate.onUserRequestedFullscreen()
        assertTrue(gate.shouldAcceptSiteFullscreenRequest())
        // Flag is one-shot; a later unsolicited retry must not pass while
        // still in the same "user left fullscreen" session unless they click.
        gate.onUserExitedFullscreen()
        assertFalse(gate.shouldAcceptSiteFullscreenRequest())
    }
}
