package com.mmhw.csvtv.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumTest {
    @Test
    fun roundTripJson() {
        val album = Album(
            title = "Trip",
            intervalMs = 5000,
            loop = true,
            items = listOf(
                Album.Item("http://x/item/0", "image/jpeg", "a.jpg", "image"),
                Album.Item("http://x/item/1", "video/mp4", "b.mp4", "video")
            )
        )
        val json = album.toJson(listOf("http://x/item/0", "http://x/item/1"))
        val parsed = Album.parse(json)
        assertEquals("Trip", parsed.title)
        assertEquals(5000L, parsed.intervalMs)
        assertTrue(parsed.loop)
        assertEquals(2, parsed.items.size)
        assertTrue(parsed.items[0].isImage)
        assertFalse(parsed.items[1].isImage)
        assertEquals("video/mp4", parsed.items[1].mime)
    }

    @Test
    fun detectsAlbumMimeAndPath() {
        assertTrue(Album.isAlbum("http://192.168.0.8:9/album.json", null))
        assertTrue(Album.isAlbum("http://192.168.0.8:9/album.json?x=1", null))
        assertTrue(Album.isAlbum("http://x/foo", Album.MIME))
        assertFalse(Album.isAlbum("http://x/photo.jpg", "image/jpeg"))
    }

    @Test
    fun parseRangeOpenEndAndSuffix() {
        val full = AlbumServer.parseRange(null, 100)
        assertEquals(0L, full.first)
        assertEquals(99L, full.second)
        assertFalse(full.third)
        val mid = AlbumServer.parseRange("bytes=10-19", 100)
        assertEquals(10L, mid.first)
        assertEquals(19L, mid.second)
        assertTrue(mid.third)
        val suffix = AlbumServer.parseRange("bytes=-10", 100)
        assertEquals(90L, suffix.first)
        assertEquals(99L, suffix.second)
    }
}
