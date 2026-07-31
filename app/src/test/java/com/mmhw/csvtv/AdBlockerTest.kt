package com.mmhw.csvtv

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdBlockerTest {

    @Before
    fun setUp() {
        AdBlocker.clearForTest()
    }

    @After
    fun tearDown() {
        AdBlocker.clearForTest()
    }

    @Test
    fun hostRule_blocksDomainAndSubdomains() {
        AdBlocker.loadFromLinesForTest(
            listOf(
                "doubleclick.net",
                "googlesyndication.com"
            )
        )
        assertTrue(AdBlocker.shouldBlock("https://ad.doubleclick.net/foo.js"))
        assertTrue(AdBlocker.shouldBlock("https://pagead2.googlesyndication.com/pagead/js"))
        assertTrue(AdBlocker.shouldBlock("https://doubleclick.net/x"))
        assertFalse(AdBlocker.shouldBlock("https://example.com/doubleclick.net/not-a-host"))
        assertFalse(AdBlocker.shouldBlock("https://www.google.com/search?q=ads"))
    }

    @Test
    fun pathRule_requiresSubstringNotBareHost() {
        AdBlocker.loadFromLinesForTest(
            listOf(
                "facebook.com/tr",
                "/pagead/"
            )
        )
        assertTrue(AdBlocker.shouldBlock("https://www.facebook.com/tr?id=1"))
        assertFalse(AdBlocker.shouldBlock("https://www.facebook.com/home"))
        assertTrue(AdBlocker.shouldBlock("https://www.google.com/pagead/ads"))
    }

    @Test
    fun hostsFileFormat_andCommentsIgnored() {
        AdBlocker.loadFromLinesForTest(
            listOf(
                "# comment",
                "",
                "0.0.0.0 ads.example.com",
                "127.0.0.1 tracker.test.org # inline",
                "! easylist comment",
                "||blocked.cdn.net^\$third-party"
            )
        )
        assertTrue(AdBlocker.shouldBlock("https://ads.example.com/banner"))
        assertTrue(AdBlocker.shouldBlock("https://sub.tracker.test.org/x"))
        assertTrue(AdBlocker.shouldBlock("https://blocked.cdn.net/script.js"))
        assertFalse(AdBlocker.shouldBlock("https://safe.example.org/"))
    }

    @Test
    fun nonHttpSchemes_notBlocked() {
        AdBlocker.loadFromLinesForTest(listOf("doubleclick.net"))
        assertFalse(AdBlocker.shouldBlock("about:blank"))
        assertFalse(AdBlocker.shouldBlock("data:text/html,hi"))
        assertFalse(AdBlocker.shouldBlock("blob:https://example.com/1"))
    }

    @Test
    fun normalizeUserHostInput_acceptsDomainAndUrl() {
        assertTrue(AdBlocker.normalizeUserHostInput("ads.example.com") == "ads.example.com")
        assertTrue(AdBlocker.normalizeUserHostInput("https://Ads.Example.com/path?x=1") == "ads.example.com")
        assertTrue(AdBlocker.normalizeUserHostInput("not a domain") == null)
        assertTrue(AdBlocker.normalizeUserHostInput("") == null)
    }
}
