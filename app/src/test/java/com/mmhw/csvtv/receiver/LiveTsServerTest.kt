package com.mmhw.csvtv.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LiveTsServerTest {
    @Test
    fun getReturnsPushedTsBytes() {
        val server = LiveTsServer()
        val url = server.start("127.0.0.1")
        try {
            Thread.sleep(50)
            val payload = ByteArray(188) { 0x5A }
            payload[0] = 0x47
            val body = CompletableFuture<ByteArray>()
            Thread {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                assertEquals(200, conn.responseCode)
                assertTrue(conn.contentType.contains("mp2t"))
                val buf = ByteArray(188)
                val n = conn.inputStream.read(buf)
                body.complete(if (n > 0) buf.copyOf(n) else ByteArray(0))
                conn.disconnect()
            }.apply { isDaemon = true; start() }
            Thread.sleep(80)
            server.push(payload, 0, payload.size, keyframe = true)
            val buf = body.get(2, TimeUnit.SECONDS)
            assertEquals(188, buf.size)
            assertEquals(0x47, buf[0].toInt() and 0xff)
            assertEquals(0x5A, buf[1].toInt() and 0xff)
        } finally {
            server.stop()
        }
    }

    @Test
    fun clientJoinsOnNextKeyframe() {
        val server = LiveTsServer(includeAudio = false)
        val url = server.start("127.0.0.1")
        try {
            Thread.sleep(50)
            val stale = ByteArray(188) { 0x11 }
            stale[0] = 0x47
            val key = ByteArray(188) { 0x22 }
            key[0] = 0x47
            server.push(stale, 0, stale.size, keyframe = true)
            val body = CompletableFuture<ByteArray>()
            Thread {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val buf = ByteArray(188)
                val n = conn.inputStream.read(buf)
                body.complete(if (n > 0) buf.copyOf(n) else ByteArray(0))
                conn.disconnect()
            }.apply { isDaemon = true; start() }
            Thread.sleep(80)
            server.push(key, 0, key.size, keyframe = true)
            val buf = body.get(2, TimeUnit.SECONDS)
            assertEquals(188, buf.size)
            assertEquals(0x22, buf[1].toInt() and 0xff)
        } finally {
            server.stop()
        }
    }
}
