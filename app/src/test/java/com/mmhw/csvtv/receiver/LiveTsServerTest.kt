package com.mmhw.csvtv.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LiveTsServerTest {
    @Test
    fun getReturnsPushedTsBytes() {
        val server = LiveTsServer()
        val url = server.start("127.0.0.1")
        try {
            Thread.sleep(50)
            val payload = ByteArray(188) { 0x47 }
            server.push(payload, 0, payload.size)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            assertEquals(200, conn.responseCode)
            assertTrue(conn.contentType.contains("mp2t"))
            val buf = ByteArray(188)
            val n = conn.inputStream.read(buf)
            assertEquals(188, n)
            assertEquals(0x47, buf[0].toInt() and 0xff)
            conn.disconnect()
        } finally {
            server.stop()
        }
    }
}
