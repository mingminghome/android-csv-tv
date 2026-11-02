package com.mmhw.csvtv.receiver

import android.util.Log
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts a shared photo/video collection on the LAN: `/album.json` plus `/item/N`.
 */
class AlbumServer {
    data class Hosted(
        val file: File,
        val mime: String,
        val title: String,
        val kind: String
    )

    private val stop = AtomicBoolean(false)
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var items: List<Hosted> = emptyList()
    private var title: String = "Collection"
    private var hostIp: String = "127.0.0.1"
    private var port: Int = 0

    @Volatile
    var publicUrl: String = ""
        private set

    fun start(hostIp: String, title: String, items: List<Hosted>): String {
        stop()
        this.hostIp = hostIp
        this.title = title.ifBlank { "Collection" }
        this.items = items
        stop.set(false)
        val server = ServerSocket(0)
        listener = server
        port = server.localPort
        publicUrl = "http://$hostIp:$port/album.json"
        acceptThread = Thread({
            server.soTimeout = 400
            while (!stop.get()) {
                try {
                    val socket = server.accept()
                    Thread({ handle(socket) }, "csvtv-album-client").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!stop.get()) break
                }
            }
            try {
                server.close()
            } catch (_: Exception) {
            }
        }, "csvtv-album-http").apply {
            isDaemon = true
            start()
        }
        instance = this
        return publicUrl
    }

    fun stop() {
        stop.set(true)
        try {
            listener?.close()
        } catch (_: Exception) {
        }
        acceptThread?.join(400)
        acceptThread = null
        listener = null
        if (instance === this) instance = null
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val raw = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            var method = "GET"
            var path = "/"
            var range: String? = null
            while (true) {
                val line = raw.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("get ") || lower.startsWith("head ")) {
                    method = line.substringBefore(' ')
                    path = line.substringAfter(' ').substringBefore(' ').substringBefore('?')
                } else if (lower.startsWith("range:")) {
                    range = line.substringAfter(':').trim()
                }
            }
            val out = socket.getOutputStream()
            val head = method.equals("HEAD", ignoreCase = true)
            when {
                path == "/" || path == "/album.json" -> writeAlbum(out, head)
                path.startsWith("/item/") -> {
                    val idx = path.removePrefix("/item/").substringBefore('/').toIntOrNull()
                    if (idx == null) writeNotFound(out) else writeItem(out, idx, range, head)
                }
                else -> writeNotFound(out)
            }
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "client", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeAlbum(out: OutputStream, head: Boolean) {
        val urls = items.indices.map { i -> "http://$hostIp:$port/item/$i" }
        val album = Album(
            title = title,
            intervalMs = Album.DEFAULT_INTERVAL_MS,
            loop = true,
            items = items.map {
                Album.Item(url = "", mime = it.mime, title = it.title, kind = it.kind)
            }
        )
        val body = album.toJson(urls).toByteArray(Charsets.UTF_8)
        writeHeaders(
            out,
            "200 OK",
            Album.MIME,
            body.size.toLong(),
            extra = "Cache-Control: no-store\r\n"
        )
        if (!head) out.write(body)
    }

    private fun writeItem(out: OutputStream, index: Int, rangeHeader: String?, head: Boolean) {
        val item = items.getOrNull(index)
        if (item == null || !item.file.isFile) {
            writeNotFound(out)
            return
        }
        val total = item.file.length()
        val (start, end, partial) = parseRange(rangeHeader, total)
        val len = (end - start + 1).coerceAtLeast(0)
        val status = if (partial) "206 Partial Content" else "200 OK"
        val extra = buildString {
            append("Accept-Ranges: bytes\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
        }
        writeHeaders(out, status, item.mime, len, extra)
        if (head || len == 0L) return
        item.file.inputStream().use { input ->
            skipFully(input, start)
            var left = len
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun writeNotFound(out: OutputStream) {
        val body = "not found".toByteArray()
        writeHeaders(out, "404 Not Found", "text/plain", body.size.toLong())
        out.write(body)
    }

    private fun writeHeaders(
        out: OutputStream,
        status: String,
        mime: String,
        length: Long,
        extra: String = ""
    ) {
        val headers =
            "HTTP/1.1 $status\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: $length\r\n" +
                extra +
                "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
    }

    companion object {
        private const val TAG = "AlbumServer"

        @Volatile
        private var instance: AlbumServer? = null

        fun start(hostIp: String, title: String, items: List<Hosted>): String {
            instance?.stop()
            return AlbumServer().start(hostIp, title, items)
        }

        fun stop() {
            instance?.stop()
            instance = null
        }

        private fun skipFully(input: java.io.InputStream, count: Long) {
            var left = count
            val buf = ByteArray(16 * 1024)
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                    continue
                }
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) return
                left -= n
            }
        }

        internal fun parseRange(header: String?, total: Long): Triple<Long, Long, Boolean> {
            if (total <= 0L) return Triple(0, 0, false)
            val last = total - 1
            val spec = header?.trim()?.removePrefix("bytes=")?.removePrefix("BYTES=")?.trim()
            if (spec.isNullOrBlank()) return Triple(0, last, false)
            if (spec.startsWith("-")) {
                val n = spec.drop(1).toLongOrNull() ?: return Triple(0, last, false)
                val count = n.coerceAtMost(total)
                return Triple(total - count, last, true)
            }
            val start = spec.substringBefore('-').toLongOrNull() ?: 0
            val endRaw = spec.substringAfter('-', "")
            val end = if (endRaw.isBlank()) last else endRaw.toLongOrNull() ?: last
            val a = start.coerceIn(0, last)
            val b = end.coerceIn(a, last)
            return Triple(a, b, a != 0L || b != last)
        }
    }
}
