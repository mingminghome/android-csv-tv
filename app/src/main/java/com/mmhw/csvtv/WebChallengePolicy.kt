package com.mmhw.csvtv

/**
 * Bot-check / captcha helpers for WebView.
 *
 * Verification widgets finish by setting a cookie on a same-site redirect or
 * a script navigation. Intercepting that load, or blocking the widget frame,
 * drops the token and the page asks again.
 */
object WebChallengePolicy {

    /** DNS labels used by verification widgets (any provider). */
    internal val CHALLENGE_HOST_LABELS = setOf(
        "challenges",
        "captcha",
        "recaptcha",
        "hcaptcha",
        "turnstile"
    )

    /** Path or query fragments that mark a verification resource. */
    internal val CHALLENGE_URL_MARKERS = listOf(
        "/cdn-cgi/challenge",
        "/cdn-cgi/l/chk",
        "challenge-platform",
        "__cf_chl_",
        "cf-challenge",
        "turnstile/",
        "/recaptcha/",
        "/hcaptcha/",
        "/captcha"
    )

    /** Document titles used by interstitial verification pages. */
    internal val CHALLENGE_TITLE_MARKERS = listOf(
        "just a moment",
        "attention required",
        "verify you are human",
        "checking if the site connection is secure",
        "needs to review the security",
        "checking your browser"
    )

    fun containsAny(haystack: String, needles: Iterable<String>): Boolean {
        if (haystack.isEmpty()) return false
        for (needle in needles) {
            if (needle.isNotEmpty() && haystack.contains(needle)) return true
        }
        return false
    }

    fun hostHasAnyLabel(host: String, labels: Set<String>): Boolean {
        val h = host.lowercase().trim('.')
        if (h.isEmpty() || labels.isEmpty()) return false
        return h.split('.').any { it in labels }
    }

    fun isChallengeHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return hostHasAnyLabel(host, CHALLENGE_HOST_LABELS)
    }

    fun isChallengeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (isChallengeHost(WebSslPolicy.hostOf(lower))) return true
        return containsAny(lower, CHALLENGE_URL_MARKERS)
    }

    /**
     * When true, [android.webkit.WebViewClient.shouldOverrideUrlLoading] must
     * return false so Chromium keeps the load (and any Set-Cookie on it).
     */
    fun shouldAllowAutoNavigation(
        currentUrl: String?,
        nextUrl: String?,
        isRedirect: Boolean,
        hasGesture: Boolean,
        isMainFrame: Boolean
    ): Boolean {
        if (!isMainFrame) return true
        if (nextUrl.isNullOrBlank()) return true
        if (hasGesture && !isRedirect) return true
        if (isChallengeUrl(nextUrl) || isChallengeUrl(currentUrl)) return true
        val curHost = WebSslPolicy.hostOf(currentUrl) ?: return false
        val nextHost = WebSslPolicy.hostOf(nextUrl) ?: return false
        return WebSslPolicy.sameSite(curHost, nextHost)
    }

    /**
     * JS helpers [isChallengePage] and [isHumanCheck] for the overlay cleaner,
     * built from the same marker lists as the Kotlin URL checks.
     */
    fun overlayGuardJs(): String {
        val urlAlt = CHALLENGE_URL_MARKERS.joinToString("|") { escapeForJsRegex(it) }
        val titleAlt = CHALLENGE_TITLE_MARKERS.joinToString("|") { escapeForJsRegex(it) }
        val widgetAlt = CHALLENGE_HOST_LABELS.joinToString("|") { escapeForJsRegex(it) }
        return """
            var __csvtvUrlRe = /$urlAlt/i;
            var __csvtvTitleRe = /$titleAlt/i;
            var __csvtvWidgetRe = /$widgetAlt/i;
            function isChallengePage() {
              try {
                var u = (location.href || '');
                if (__csvtvUrlRe.test(u) || __csvtvWidgetRe.test(u)) return true;
                var title = (document.title || '');
                if (__csvtvTitleRe.test(title)) return true;
                return !!document.querySelector(
                  'iframe[src*="challenge"],iframe[src*="captcha"],iframe[src*="turnstile"],iframe[src*="recaptcha"],iframe[src*="hcaptcha"],' +
                  '[class*="challenge"],[id*="challenge"],[class*="captcha"],[id*="captcha"],' +
                  '[class*="turnstile"],[id*="turnstile"],[name*="captcha"],[name*="turnstile"],[name*="recaptcha"]'
                );
              } catch (e) { return false; }
            }
            function isHumanCheck(el) {
              try {
                if (!el) return false;
                var id = (el.id || '') + '';
                var cls = (typeof el.className === 'string') ? el.className :
                  (el.className && el.className.baseVal) || '';
                var t = (id + ' ' + cls);
                if (__csvtvWidgetRe.test(t) || __csvtvUrlRe.test(t)) return true;
                if (el.querySelector && el.querySelector(
                  'iframe[src*="challenge"],iframe[src*="captcha"],iframe[src*="turnstile"],iframe[src*="recaptcha"],iframe[src*="hcaptcha"]'
                )) return true;
                var txt = ((el.innerText || el.textContent || '') + '');
                if (__csvtvTitleRe.test(txt)) return true;
              } catch (e) {}
              return false;
            }
        """.trimIndent()
    }

    internal fun escapeForJsRegex(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '\\', '.', '*', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '/' -> {
                    sb.append('\\').append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
