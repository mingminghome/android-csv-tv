package com.mmhw.csvtv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsStreamRoutingTest {

    @Test
    fun htmlLiveTypePage_isNotIptvAndOpensWebView() {
        val url = "https://yuyantv.cn/liveType.html"
        assertFalse(Utils.looksLikeIptvStreamUrl(url))
        assertFalse(Utils.isVideoStream(url, "text/html"))
        assertFalse(Utils.isVideoStream(url, "text/html; charset=utf-8"))
        assertFalse(Utils.shouldOpenInNativePlayer(url, "text/html", "LINK"))
    }

    @Test
    fun livePathSegment_isIptv() {
        assertTrue(Utils.looksLikeIptvStreamUrl("https://cdn.example.com/live/channel1"))
        assertTrue(Utils.looksLikeIptvStreamUrl("https://cdn.example.com/live/?id=1"))
        assertTrue(Utils.looksLikeIptvStreamUrl("https://example.com/live"))
    }

    @Test
    fun realHlsAndPhpGateway_stillNative() {
        assertTrue(Utils.looksLikeIptvStreamUrl("https://cdn.example.com/index.m3u8"))
        assertTrue(Utils.looksLikeIptvStreamUrl("https://example.com/163189.php?id=viu"))
        assertTrue(Utils.shouldOpenInNativePlayer(
            "https://cdn.qd.je/163189/fct2",
            "application/vnd.apple.mpegurl",
            "M3U8"
        ))
    }

    @Test
    fun htmlContentType_doesNotOverrideM3u8Url() {
        assertTrue(Utils.isVideoStream("https://cdn.example.com/live.m3u8", "text/html"))
        assertTrue(Utils.shouldOpenInNativePlayer("https://cdn.example.com/a.m3u8", "text/html", "M3U8"))
    }

    @Test
    fun htmlListingUnderLivePath_opensWebView() {
        val url = "https://example.com/classify/live/?type=-1"
        assertFalse(Utils.isVideoStream(url, "text/html"))
        assertFalse(Utils.shouldOpenInNativePlayer(url, "text/html", "LINK"))
        assertFalse(Utils.shouldOpenInNativePlayer(url, "text/html; charset=utf-8", "LINK"))
    }

    @Test
    fun htmlLiveRoomPath_opensWebView() {
        val url = "https://example.com/live/000003589"
        assertFalse(Utils.isVideoStream(url, "text/html; charset=utf-8"))
        assertFalse(Utils.shouldOpenInNativePlayer(url, "text/html; charset=utf-8", "LINK"))
    }
}
