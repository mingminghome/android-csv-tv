package com.mmhw.csvtv

/**
 * WebView [android.webkit.WebViewClient.onReceivedError] fires for subresources as
 * well as the page. Leaving browse on every failure would bounce the user off a
 * verification hop or a later navigation that Chromium can still recover.
 */
object WebViewLoadErrorPolicy {

    fun shouldAnnounce(isMainFrame: Boolean): Boolean = isMainFrame

    fun shouldExitToBrowse(isMainFrame: Boolean, initialLoadCompleted: Boolean): Boolean {
        return isMainFrame && !initialLoadCompleted
    }
}
