package com.mmhw.csvtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSslPolicyTest {

    @Test
    fun blockedAd_isCancelledSilently() {
        assertEquals(
            WebSslAction.CANCEL_SILENT,
            WebSslPolicy.decide(
                pageUrl = "https://watch.example.com/post/1.html",
                failingUrl = "https://ads.doubleclick.net/pixel",
                isBlockedAd = true
            )
        )
    }

    @Test
    fun thirdPartyJunk_doesNotPrompt() {
        assertEquals(
            WebSslAction.PROCEED_SILENT,
            WebSslPolicy.decide(
                pageUrl = "https://watch.example.com/post/1.html?ep=2",
                failingUrl = "https://spam-cdn.invalid/banner.js",
                isBlockedAd = false
            )
        )
    }

    @Test
    fun mainPage_prompts() {
        assertEquals(
            WebSslAction.PROMPT,
            WebSslPolicy.decide(
                pageUrl = "https://watch.example.com/post/1.html",
                failingUrl = "https://watch.example.com/post/1.html",
                isBlockedAd = false
            )
        )
    }

    @Test
    fun sameSiteSubdomain_prompts() {
        assertEquals(
            WebSslAction.PROMPT,
            WebSslPolicy.decide(
                pageUrl = "https://watch.example.com/post/1.html",
                failingUrl = "https://player.watch.example.com/embed",
                isBlockedAd = false
            )
        )
    }

    @Test
    fun missingFailingUrl_isCancelled() {
        assertEquals(
            WebSslAction.CANCEL_SILENT,
            WebSslPolicy.decide(
                pageUrl = "https://example.com/",
                failingUrl = null,
                isBlockedAd = false
            )
        )
    }

    @Test
    fun hostOf_parsesHttps() {
        assertEquals(
            "watch.example.com",
            WebSslPolicy.hostOf("https://watch.example.com/post/1.html?ep=1")
        )
    }

    @Test
    fun sameSite_doesNotMatchLookalike() {
        assertFalse(WebSslPolicy.sameSite("example.com", "evil-example.com"))
        assertTrue(WebSslPolicy.sameSite("example.com", "www.example.com"))
    }
}
