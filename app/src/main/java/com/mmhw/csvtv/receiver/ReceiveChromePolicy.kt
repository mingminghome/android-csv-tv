package com.mmhw.csvtv.receiver

/**
 * Overlay Back, Fit/Fill, and the collection badge hide after a short idle
 * so the picture is clean. Tap or a remote key brings them back.
 */
object ReceiveChromePolicy {
    const val AUTO_HIDE_MS = 3500L

    fun autoHides(
        showingMedia: Boolean,
        web: Boolean,
        confirm: Boolean,
        idle: Boolean
    ): Boolean {
        return showingMedia && !web && !confirm && !idle
    }
}
