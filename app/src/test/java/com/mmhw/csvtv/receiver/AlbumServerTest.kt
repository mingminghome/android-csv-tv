package com.mmhw.csvtv.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AlbumServerTest {
    @Test
    fun servesAlbumJsonAndItem() {
        val dir = File.createTempFile("album", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            val photo = File(dir, "0.jpg")
            photo.writeBytes(ByteArray(32) { 0x11 })
            val url = AlbumServer.start(
                "127.0.0.1",
                "Trip",
                listOf(
                    AlbumServer.Hosted(photo, "image/jpeg", "0.jpg", "image")
                )
            )
            Thread.sleep(80)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val album = Album.parse(body)
            assertEquals("Trip", album.title)
            assertEquals(1, album.items.size)
            assertTrue(album.items[0].url.contains("/item/0"))
            val item = URL(album.items[0].url).openConnection() as HttpURLConnection
            item.connectTimeout = 2000
            item.readTimeout = 2000
            assertEquals(200, item.responseCode)
            assertEquals(32, item.inputStream.readBytes().size)
            item.disconnect()
            val ranged = URL(album.items[0].url).openConnection() as HttpURLConnection
            ranged.setRequestProperty("Range", "bytes=8-15")
            ranged.connectTimeout = 2000
            ranged.readTimeout = 2000
            assertEquals(206, ranged.responseCode)
            assertEquals(8, ranged.inputStream.readBytes().size)
            ranged.disconnect()
        } finally {
            AlbumServer.stop()
            dir.deleteRecursively()
        }
    }
}
