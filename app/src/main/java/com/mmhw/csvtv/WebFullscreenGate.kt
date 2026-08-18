package com.mmhw.csvtv

/**
 * Stops streaming sites from trapping the user in HTML5 fullscreen.
 *
 * Many embed players call requestFullscreen() on play (and again on
 * fullscreenchange after Back). After the user leaves fullscreen, later
 * site-initiated requests are ignored until they click the page (new
 * episode), press the fullscreen control, or type a new address.
 *
 * Automatic loads (Cloudflare hops, `?vod=` player updates) must not
 * unlock — those are how sites like xiaoyakankan reopen fullscreen.
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
