package com.mmhw.csvtv

import java.net.URI

/**
 * WebView [android.webkit.WebViewClient.onReceivedSslError] fires for every
 * resource, not just the page. Ad / tracker frames with junk certificates
 * would otherwise spam a Proceed dialog on TV.
 */
enum class WebSslAction {
    /** Top-level (or same-site) page — ask once. */
    PROMPT,
    /** Third-party host (player CDN, etc.) — no dialog. */
    PROCEED_SILENT,
    /** Blocked ad/tracker — fail the request, no dialog. */
    CANCEL_SILENT
}

object WebSslPolicy {
    fun decide(pageUrl: String?, failingUrl: String?, isBlockedAd: Boolean): WebSslAction {
        if (isBlockedAd) return WebSslAction.CANCEL_SILENT
        val failHost = hostOf(failingUrl) ?: return WebSslAction.CANCEL_SILENT
        val pageHost = hostOf(pageUrl)
        if (pageHost != null && !sameSite(pageHost, failHost)) {
            return WebSslAction.PROCEED_SILENT
        }
        return WebSslAction.PROMPT
    }

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (trimmed.startsWith("about:") || trimmed.startsWith("data:")) return null
        return try {
            val withScheme =
                if (trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true)
                ) {
                    trimmed
                } else {
                    "https://$trimmed"
                }
            URI(withScheme).host?.lowercase()?.trim('.')?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun sameSite(pageHost: String, failHost: String): Boolean {
        if (pageHost == failHost) return true
        return failHost.endsWith(".$pageHost") || pageHost.endsWith(".$failHost")
    }
}
