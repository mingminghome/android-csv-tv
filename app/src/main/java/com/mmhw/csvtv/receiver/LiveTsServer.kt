package com.mmhw.csvtv.receiver

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves a live MPEG-TS byte stream over HTTP (no Content-Length) so ExoPlayer
 * can play phone screen capture as a video URL.
 *
 * A new client waits for the next keyframe, then follows live writes. That
 * keeps continuity counters sequential without replaying an old GOP.
 */
class LiveTsServer(
    private val mime: String = "video/mp2t",
    private val ringBytes: Int = 188 * 11_000,
    private val includeAudio: Boolean = true
) {
    private val stop = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val pending = CopyOnWriteArrayList<OutputStream>()
    private val ring = ByteRing(ringBytes)
    private val gop = ByteArrayOutputStream()
    private val clientLock = Any()
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    var onClient: (() -> Unit)? = null

    @Volatile
    var publicUrl: String = ""
        private set

    fun start(hostIp: String): String {
        stop.set(false)
        val server = ServerSocket(0)
        listener = server
        val port = server.localPort
        publicUrl = "http://$hostIp:$port/screen.ts"
        acceptThread = Thread({
            server.soTimeout = 400
            while (!stop.get()) {
                try {
                    val socket = server.accept()
                    Thread({ handle(socket) }, "csvtv-live-client").apply {
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
        }, "csvtv-live-http").apply {
            isDaemon = true
            start()
        }
        return publicUrl
    }

    fun push(data: ByteArray, off: Int, len: Int, keyframe: Boolean = false) {
        if (len <= 0 || stop.get()) return
        synchronized(clientLock) {
            if (keyframe) {
                gop.reset()
            }
            if (keyframe || (gop.size() in 1 until ringBytes)) {
                gop.write(data, off, len)
            }
            ring.write(data, off, len)
            val dead = ArrayList<OutputStream>()
            for (c in clients) {
                try {
                    c.write(data, off, len)
                    c.flush()
                } catch (_: Exception) {
                    dead.add(c)
                }
            }
            if (dead.isNotEmpty()) {
                clients.removeAll(dead.toSet())
            }
            if (keyframe) {
                val start = gop.toByteArray()
                val joined = ArrayList<OutputStream>()
                for (c in pending) {
                    try {
                        if (start.isNotEmpty()) {
                            c.write(start)
                        } else {
                            c.write(MpegTsMuxer.bootstrapPsi(includeAudio))
                        }
                        c.flush()
                        clients.add(c)
                        joined.add(c)
                    } catch (_: Exception) {
                        try {
                            c.close()
                        } catch (_: Exception) {
                        }
                    }
                }
                pending.removeAll(joined.toSet())
            }
        }
    }

    fun stop() {
        stop.set(true)
        try {
            listener?.close()
        } catch (_: Exception) {
        }
        synchronized(clientLock) {
            for (c in clients) {
                try {
                    c.close()
                } catch (_: Exception) {
                }
            }
            clients.clear()
            for (c in pending) {
                try {
                    c.close()
                } catch (_: Exception) {
                }
            }
            pending.clear()
            gop.reset()
        }
        acceptThread?.join(500)
        acceptThread = null
        listener = null
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            try {
                socket.sendBufferSize = 256 * 1024
            } catch (_: Exception) {
            }
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            var method = "GET"
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("GET ") || line.startsWith("HEAD ") ||
                    line.startsWith("get ") || line.startsWith("head ")
                ) {
                    method = line.substringBefore(' ')
                }
            }
            val out = socket.getOutputStream()
            val headers = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Cache-Control: no-store, no-cache\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Accept-Ranges: none\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            val isHead = method.equals("HEAD", ignoreCase = true)
            out.write(headers)
            out.flush()
            if (isHead) {
                socket.close()
                return
            }
            pending.add(out)
            try {
                onClient?.invoke()
            } catch (_: Exception) {
            }
            var waits = 0
            while (pending.contains(out) && waits < 80 && !stop.get()) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
                waits++
            }
            synchronized(clientLock) {
                if (pending.remove(out)) {
                    val start = gop.toByteArray()
                    if (start.isNotEmpty()) {
                        out.write(start)
                    } else {
                        out.write(MpegTsMuxer.bootstrapPsi(includeAudio))
                    }
                    out.flush()
                    clients.add(out)
                }
            }
            while (!stop.get() && (pending.contains(out) || clients.contains(out))) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
