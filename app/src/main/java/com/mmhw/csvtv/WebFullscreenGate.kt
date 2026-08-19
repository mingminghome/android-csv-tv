package com.mmhw.csvtv

/**
 * Stops streaming pages from trapping the user in an auto-opened surface
 * (HTML5 fullscreen or native player handoff).
 *
 * Embed players often request fullscreen on play, and HLS pages often
 * fetch another playlist as soon as Back leaves the native player.
 * After the user dismisses that surface, later site-initiated opens are
 * ignored until they click the page, press a control, or type a new
 * address. Automatic loads (Cloudflare hops, query-only player updates)
 * must not unlock.
 */
class WebFullscreenGate(
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    var suppressUntilUserAction: Boolean = false
        private set

    private var cooldownUntilMs: Long = 0L
    private var allowNextSiteRequest: Boolean = false

    fun onUserExitedFullscreen() {
        suppressUntilUserAction = true
        allowNextSiteRequest = false
        cooldownUntilMs = clockMs() + HIDE_COOLDOWN_MS
    }

    /** Site or WebView hid fullscreen (same trap as Back if they re-request immediately). */
    fun onFullscreenHidden() {
        cooldownUntilMs = maxOf(cooldownUntilMs, clockMs() + HIDE_COOLDOWN_MS)
    }

    fun onUserRequestedFullscreen() {
        suppressUntilUserAction = false
        cooldownUntilMs = 0L
        allowNextSiteRequest = true
    }

    fun onUserPageClick() {
        suppressUntilUserAction = false
        cooldownUntilMs = 0L
    }

    fun onNavigatedToNewPage() {
        suppressUntilUserAction = false
        cooldownUntilMs = 0L
        allowNextSiteRequest = false
    }

    fun shouldAcceptSiteFullscreenRequest(): Boolean {
        if (allowNextSiteRequest) {
            allowNextSiteRequest = false
            return true
        }
        if (suppressUntilUserAction) return false
        if (clockMs() < cooldownUntilMs) return false
        return true
    }

    companion object {
        const val HIDE_COOLDOWN_MS = 2500L
    }
}
