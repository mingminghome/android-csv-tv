package com.mmhw.csvtv.receiver

/**
 * Fixed circular buffer so a late HTTP client still sees recent MPEG-TS
 * (PAT/PMT + a keyframe) without blocking the encoder.
 */
class ByteRing(private val cap: Int) {
    init {
        require(cap > 0)
    }

    private val buf = ByteArray(cap)
    private var start = 0
    private var length = 0

    @Synchronized
    fun write(src: ByteArray, off: Int, n: Int) {
        if (n <= 0) return
        if (n >= cap) {
            System.arraycopy(src, off + n - cap, buf, 0, cap)
            start = 0
            length = cap
            return
        }
        var remain = n
        var i = off
        while (remain > 0) {
            val tail = (start + length) % cap
            val space = minOf(remain, cap - tail)
            System.arraycopy(src, i, buf, tail, space)
            i += space
            remain -= space
            length += space
            if (length > cap) {
                val overflow = length - cap
                start = (start + overflow) % cap
                length = cap
            }
        }
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(length)
        if (length == 0) return out
        val first = minOf(length, cap - start)
        System.arraycopy(buf, start, out, 0, first)
        if (length > first) {
            System.arraycopy(buf, 0, out, first, length - first)
        }
        return out
    }

    /** Newest bytes starting at a 0x47 MPEG-TS sync, trimmed to whole packets. */
    fun snapshotTs(packet: Int = 188): ByteArray {
        val raw = snapshot()
        if (raw.size < packet) return ByteArray(0)
        val search = minOf(raw.size - packet, packet * 4)
        var i = 0
        while (i <= search) {
            if ((raw[i].toInt() and 0xff) == 0x47) {
                val next = i + packet
                if (next >= raw.size || (raw[next].toInt() and 0xff) == 0x47) {
                    val aligned = (raw.size - i) / packet * packet
                    if (aligned < packet) return ByteArray(0)
                    return raw.copyOfRange(i, i + aligned)
                }
            }
            i++
        }
        return ByteArray(0)
    }

    @Synchronized
    fun size(): Int = length
}
