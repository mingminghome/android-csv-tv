package com.mmhw.csvtv.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMediaTest {

    @Test
    fun httpUrlsAreSentDirectly() {
        assertTrue(SendMedia.isDirectHttpUrl("https://cdn.example/live.m3u8"))
        assertTrue(SendMedia.isDirectHttpUrl("http://192.168.3.10/clip.mp4"))
        assertFalse(SendMedia.isDirectHttpUrl("content://media/external/video/1"))
    }

    @Test
    fun videoMimeFromPathAndDeclared() {
        assertEquals("video/mp4", SendMedia.mimeOf(null, "clip.mp4"))
        assertEquals(
            "application/vnd.apple.mpegurl",
            SendMedia.mimeOf(null, "https://cdn.example/index.m3u8?token=1")
        )
        assertEquals("image/jpeg", SendMedia.mimeOf("image/jpeg", "blob"))
        assertEquals("video/mp4", SendMedia.mimeOf("video/*", "content://media/1"))
        assertEquals("image/jpeg", SendMedia.mimeOf("*/*", "photo.jpg"))
        assertTrue(SendMedia.isVideo("video/mp4"))
        assertTrue(SendMedia.isVideo("video/mp2t"))
        assertTrue(SendMedia.isVideo("application/vnd.apple.mpegurl"))
        assertFalse(SendMedia.isVideo("image/png"))
    }

    @Test
    fun shareTextExtractsWebPage() {
        val url = SendMedia.extractHttpUrl("Example Domain\nhttps://example.com/news")
        assertEquals("https://example.com/news", url)
        assertTrue(SendMedia.isWebPage(url!!))
        assertTrue(SendMedia.isWebPage("https://example.com/news", "text/html"))
        assertFalse(SendMedia.isWebPage("https://cdn.example/clip.mp4"))
        assertFalse(SendMedia.isWebPage("https://cdn.example/live.m3u8"))
    }

    @Test
    fun extensionFollowsMime() {
        assertEquals("mp4", SendMedia.extensionOf("video/mp4", "x"))
        assertEquals("mkv", SendMedia.extensionOf("video/x-matroska", "x"))
        assertEquals("jpg", SendMedia.extensionOf("image/jpeg", "x"))
        assertEquals("m3u8", SendMedia.extensionOf("application/vnd.apple.mpegurl", "x"))
    }

    @Test
    fun mediaKindPrefersDeclaredThenMimeThenPath() {
        assertEquals(
            NativeReceiver.MEDIA_WEB,
            SendMedia.mediaKind("https://cdn.example/clip.mp4", "video/mp4", NativeReceiver.MEDIA_WEB)
        )
        assertEquals(
            NativeReceiver.MEDIA_IMAGE,
            SendMedia.mediaKind("blob", "image/jpeg")
        )
        assertEquals(
            NativeReceiver.MEDIA_VIDEO,
            SendMedia.mediaKind("https://cdn.example/live.ts", "video/mp2t")
        )
        assertEquals(
            NativeReceiver.MEDIA_AUDIO,
            SendMedia.mediaKind("track.m4a", "audio/mp4")
        )
        assertEquals(
            NativeReceiver.MEDIA_WEB,
            SendMedia.mediaKind("https://example.com/news", "text/html")
        )
        assertEquals(
            NativeReceiver.MEDIA_IMAGE,
            SendMedia.mediaKind("https://cdn.example/photo.avif")
        )
        assertEquals(
            NativeReceiver.MEDIA_AUDIO,
            SendMedia.mediaKind("https://cdn.example/song.ogg")
        )
        assertEquals(
            NativeReceiver.MEDIA_AUDIO,
            SendMedia.mediaKind("https://cdn.example/track.wma")
        )
        assertEquals(
            NativeReceiver.MEDIA_VIDEO,
            SendMedia.mediaKind("https://cdn.example/clip.mkv")
        )
        assertEquals(
            NativeReceiver.MEDIA_WEB,
            SendMedia.mediaKind("https://photos.google.com/share/album")
        )
    }
}
