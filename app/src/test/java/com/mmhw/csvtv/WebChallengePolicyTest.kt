package com.mmhw.csvtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebChallengePolicyTest {

    @Test
    fun containsAny_and_hostHasAnyLabel() {
        assertTrue(WebChallengePolicy.containsAny("https://x.example/turnstile/v0/api.js", listOf("turnstile/")))
        assertFalse(WebChallengePolicy.containsAny("https://example.com/live", listOf("turnstile/")))
        assertTrue(WebChallengePolicy.hostHasAnyLabel("challenges.cdn.example", setOf("challenges")))
        assertTrue(WebChallengePolicy.hostHasAnyLabel("www.recaptcha.net", setOf("recaptcha")))
        assertFalse(WebChallengePolicy.hostHasAnyLabel("static.cdninsights.example", setOf("challenges")))
    }

    @Test
    fun challengeHostsAndPaths() {
        assertTrue(WebChallengePolicy.isChallengeHost("challenges.example.net"))
        assertTrue(WebChallengePolicy.isChallengeHost("foo.challenges.example.net"))
        assertTrue(WebChallengePolicy.isChallengeHost("hcaptcha.com"))
        assertTrue(WebChallengePolicy.isChallengeUrl("https://challenges.example.net/cdn-cgi/challenge-platform/x"))
        assertTrue(
            WebChallengePolicy.isChallengeUrl(
                "https://watch.example.com/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page"
            )
        )
        assertTrue(WebChallengePolicy.isChallengeUrl("https://www.google.com/recaptcha/api.js"))
        assertTrue(WebChallengePolicy.isChallengeUrl("https://example.com/live?__cf_chl_tk=abc"))
        assertFalse(WebChallengePolicy.isChallengeUrl("https://example.com/live"))
        assertFalse(WebChallengePolicy.isChallengeHost("static.cdninsights.example"))
    }

    @Test
    fun iframeLoadsAreNeverIntercepted() {
        assertTrue(
            WebChallengePolicy.shouldAllowAutoNavigation(
                "https://example.com/live",
                "https://ads.evil/tracker",
                isRedirect = true,
                hasGesture = false,
                isMainFrame = false
            )
        )
    }

    @Test
    fun sameSitePostChallengeRedirectIsAllowed() {
        assertTrue(
            WebChallengePolicy.shouldAllowAutoNavigation(
                "https://watch.example.com/cdn-cgi/challenge-platform/x",
                "https://watch.example.com/live",
                isRedirect = true,
                hasGesture = false,
                isMainFrame = true
            )
        )
        assertTrue(
            WebChallengePolicy.shouldAllowAutoNavigation(
                "https://watch.example.com/live",
                "https://watch.example.com/live",
                isRedirect = false,
                hasGesture = false,
                isMainFrame = true
            )
        )
    }

    @Test
    fun crossSiteScriptRedirectStillBlocked() {
        assertFalse(
            WebChallengePolicy.shouldAllowAutoNavigation(
                "https://watch.example.com/live",
                "https://malware.example/phish",
                isRedirect = false,
                hasGesture = false,
                isMainFrame = true
            )
        )
    }

    @Test
    fun userGestureNavigationIsAllowed() {
        assertTrue(
            WebChallengePolicy.shouldAllowAutoNavigation(
                "https://watch.example.com/live",
                "https://other.example/page",
                isRedirect = false,
                hasGesture = true,
                isMainFrame = true
            )
        )
    }

    @Test
    fun overlayGuardJs_usesSharedMarkers() {
        val js = WebChallengePolicy.overlayGuardJs()
        assertTrue(js.contains("function isChallengePage"))
        assertTrue(js.contains("function isHumanCheck"))
        assertTrue(js.contains("cdn-cgi\\/challenge"))
        assertEquals("\\/cdn-cgi\\/challenge", WebChallengePolicy.escapeForJsRegex("/cdn-cgi/challenge"))
    }
}
