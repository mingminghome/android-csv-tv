package com.mmhw.csvtv

/**
 * Limits browse-time probing so catalog HEAD checks do not compete with
 * playback or WebView once the user leaves the home grid.
 */
object CatalogNetworkPolicy {
    const val PRECHECK_GAP_MS = 1500L
    const val FOCUS_PROBE_INITIAL_MS = 30_000L
    const val FOCUS_PROBE_REPEAT_MS = 120_000L
    const val ADBLOCK_AUTO_UPDATE_DELAY_MS = 60_000L

    fun isUtilityUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u == "settings" || u == "refresh" || u == "browser" || u == "update"
    }

    fun shouldSkipPrecheck(video: Video): Boolean {
        if (isUtilityUrl(video.url)) return true
        if (video.isValid != null) return true
        return false
    }
}
