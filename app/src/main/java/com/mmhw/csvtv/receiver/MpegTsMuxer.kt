package com.mmhw.csvtv.receiver

import java.io.ByteArrayOutputStream

/**
 * Minimal MPEG-TS muxer for H.264 (and optional AAC). Packets are 188 bytes
 * so a live HTTP body can be played by ExoPlayer as `video/mp2t`.
 */
class MpegTsMuxer(private val includeAudio: Boolean = true) {
    private var patCc = 0
    private var pmtCc = 0
    private var videoCc = 0
    private var audioCc = 0
    private var videoPackets = 0

    fun muxVideo(accessUnit: ByteArray, ptsUs: Long, keyframe: Boolean): ByteArray {
        val pts90 = (ptsUs * 9L) / 100L
        val pes = pesPacket(accessUnit, pts90, streamId = 0xE0, setLength = false)
        val out = ByteArrayOutputStream(188 * 8)
        if (keyframe || videoPackets % 15 == 0) {
            out.write(psiBootstrap())
        }
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val remaining = pes.size - offset
            val wantPcr = first
            val wantRai = first && keyframe
            val minAfPayload = when {
                wantPcr -> 7
                wantRai -> 1
                else -> 0
            }
            val minAfTotal = if (minAfPayload > 0) 1 + minAfPayload else 0
            val maxPayload = 184 - minAfTotal
            val take = minOf(remaining, maxPayload)
            val pad = 184 - minAfTotal - take
            val pkt = ByteArray(PACKET)
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0x00) or (PID_VIDEO shr 8)).toByte()
            pkt[2] = (PID_VIDEO and 0xFF).toByte()
            var i = 4
            if (minAfTotal + pad > 0) {
                pkt[3] = (0x30 or (videoCc and 0x0F)).toByte()
                val afLenVal = minAfTotal + pad - 1
                pkt[i++] = afLenVal.toByte()
                var written = 0
                if (minAfPayload > 0) {
                    val flags = (if (wantPcr) 0x10 else 0) or (if (wantRai) 0x40 else 0)
                    pkt[i++] = flags.toByte()
                    written++
                    if (wantPcr) {
                        writePcr(pkt, i, pts90)
                        i += 6
                        written += 6
                    }
                }
                val stuff = afLenVal - written
                for (s in 0 until stuff) {
                    pkt[i++] = 0xFF.toByte()
                }
            } else {
                pkt[3] = (0x10 or (videoCc and 0x0F)).toByte()
            }
            videoCc = (videoCc + 1) and 0x0F
            System.arraycopy(pes, offset, pkt, i, take)
            offset += take
            out.write(pkt)
            first = false
            videoPackets++
        }
        return out.toByteArray()
    }

    fun muxAudio(adtsFrame: ByteArray, ptsUs: Long): ByteArray {
        val pts90 = (ptsUs * 9L) / 100L
        val pes = pesPacket(adtsFrame, pts90, streamId = 0xC0, setLength = true)
        val out = ByteArrayOutputStream(188 * 4)
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val remaining = pes.size - offset
            val take = minOf(remaining, 184)
            val pad = 184 - take
            val pkt = ByteArray(PACKET)
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0x00) or (PID_AUDIO shr 8)).toByte()
            pkt[2] = (PID_AUDIO and 0xFF).toByte()
            var i = 4
            if (pad > 0) {
                pkt[3] = (0x30 or (audioCc and 0x0F)).toByte()
                val afLenVal = pad - 1
                pkt[i++] = afLenVal.toByte()
                for (s in 0 until afLenVal) {
                    pkt[i++] = 0xFF.toByte()
                }
            } else {
                pkt[3] = (0x10 or (audioCc and 0x0F)).toByte()
            }
            audioCc = (audioCc + 1) and 0x0F
            System.arraycopy(pes, offset, pkt, i, take)
            offset += take
            out.write(pkt)
            first = false
        }
        return out.toByteArray()
    }

    companion object {
        const val PACKET = 188
        const val PID_PAT = 0
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        const val PID_AUDIO = 0x0101

        fun bootstrapPsi(includeAudio: Boolean = true): ByteArray =
            MpegTsMuxer(includeAudio).psiBootstrap()

        /** True when [annexB] contains an IDR slice (NAL type 5). */
        fun isIdr(annexB: ByteArray): Boolean {
            var i = 0
            while (i + 3 < annexB.size) {
                val nal = when {
                    i + 4 < annexB.size &&
                        annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte() &&
                        annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte() -> i + 4
                    annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte() &&
                        annexB[i + 2] == 1.toByte() -> i + 3
                    else -> {
                        i++
                        continue
                    }
                }
                if (nal < annexB.size && (annexB[nal].toInt() and 0x1F) == 5) return true
                i = nal
            }
            return false
        }

        fun toAnnexB(raw: ByteArray): ByteArray {
            if (raw.isEmpty()) return raw
            if (raw.size >= 4 && raw[0] == 0.toByte() && raw[1] == 0.toByte() &&
                (raw[2] == 1.toByte() || (raw[2] == 0.toByte() && raw[3] == 1.toByte()))
            ) {
                return raw
            }
            if (raw.size >= 7 && raw[0] == 1.toByte()) {
                val avcc = avccToAnnexB(raw)
                if (avcc.isNotEmpty()) return avcc
            }
            val out = ByteArrayOutputStream(raw.size + 16)
            var i = 0
            while (i + 4 <= raw.size) {
                val n = ((raw[i].toInt() and 0xff) shl 24) or
                    ((raw[i + 1].toInt() and 0xff) shl 16) or
                    ((raw[i + 2].toInt() and 0xff) shl 8) or
                    (raw[i + 3].toInt() and 0xff)
                i += 4
                if (n <= 0 || i + n > raw.size) break
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(raw, i, n)
                i += n
            }
            return if (out.size() == 0) raw else out.toByteArray()
        }

        /** avcC decoder-config record → Annex-B SPS/PPS. Empty if the bytes are not avcC. */
        fun avccToAnnexB(avcc: ByteArray): ByteArray {
            if (avcc.size < 7 || avcc[0] != 1.toByte()) return ByteArray(0)
            var i = 5
            val numSps = avcc[i++].toInt() and 0x1F
            val out = ByteArrayOutputStream()
            repeat(numSps) {
                if (i + 2 > avcc.size) return ByteArray(0)
                val n = ((avcc[i].toInt() and 0xff) shl 8) or (avcc[i + 1].toInt() and 0xff)
                i += 2
                if (n <= 0 || i + n > avcc.size) return ByteArray(0)
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(avcc, i, n)
                i += n
            }
            if (i >= avcc.size) return out.toByteArray()
            val numPps = avcc[i++].toInt() and 0xff
            repeat(numPps) {
                if (i + 2 > avcc.size) return ByteArray(0)
                val n = ((avcc[i].toInt() and 0xff) shl 8) or (avcc[i + 1].toInt() and 0xff)
                i += 2
                if (n <= 0 || i + n > avcc.size) return ByteArray(0)
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(avcc, i, n)
                i += n
            }
            return out.toByteArray()
        }

        fun audNal(): ByteArray = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        fun adtsFrame(aac: ByteArray, sampleRate: Int = 44100, channels: Int = 2): ByteArray {
            val srIndex = when (sampleRate) {
                48000 -> 3
                44100 -> 4
                32000 -> 5
                24000 -> 6
                22050 -> 7
                else -> 4
            }
            val profile = 1
            val frameLen = aac.size + 7
            val header = ByteArray(7)
            header[0] = 0xFF.toByte()
            header[1] = 0xF1.toByte()
            header[2] = (((profile and 3) shl 6) or (srIndex shl 2) or ((channels shr 2) and 1)).toByte()
            header[3] = (((channels and 3) shl 6) or ((frameLen shr 11) and 3)).toByte()
            header[4] = ((frameLen shr 3) and 0xFF).toByte()
            header[5] = (((frameLen and 7) shl 5) or 0x1F).toByte()
            header[6] = 0xFC.toByte()
            val out = ByteArray(7 + aac.size)
            System.arraycopy(header, 0, out, 0, 7)
            System.arraycopy(aac, 0, out, 7, aac.size)
            return out
        }

        internal fun mpegCrc(data: ByteArray, off: Int = 0, len: Int = data.size): Int {
            var crc = -1
            for (i in off until off + len) {
                crc = crc xor ((data[i].toInt() and 0xff) shl 24)
                repeat(8) {
                    crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
                }
            }
            return crc
        }
    }

    private fun pesPacket(
        payload: ByteArray,
        pts90: Long,
        streamId: Int,
        setLength: Boolean
    ): ByteArray {
        val header = ByteArray(14)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = streamId.toByte()
        val pesLen = if (setLength) (8 + payload.size).coerceAtMost(65535) else 0
        header[4] = ((pesLen shr 8) and 0xFF).toByte()
        header[5] = (pesLen and 0xFF).toByte()
        header[6] = 0x84.toByte() // data_alignment: payload starts at an access unit
        header[7] = 0x80.toByte()
        header[8] = 0x05
        writePts(header, 9, pts90)
        val out = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(payload, 0, out, header.size, payload.size)
        return out
    }

    private fun writePts(dest: ByteArray, off: Int, pts: Long) {
        dest[off] = (0x20 or (((pts shr 29) and 0x0E).toInt()) or 0x01).toByte()
        dest[off + 1] = ((pts shr 22) and 0xFF).toByte()
        dest[off + 2] = ((((pts shr 14) and 0xFFFE) or 1L).toInt()).toByte()
        dest[off + 3] = ((pts shr 7) and 0xFF).toByte()
        dest[off + 4] = ((((pts shl 1) and 0xFFFE) or 1L).toInt()).toByte()
    }

    private fun writePcr(dest: ByteArray, off: Int, pts90: Long) {
        val pcrBase = pts90
        dest[off] = ((pcrBase shr 25) and 0xFF).toByte()
        dest[off + 1] = ((pcrBase shr 17) and 0xFF).toByte()
        dest[off + 2] = ((pcrBase shr 9) and 0xFF).toByte()
        dest[off + 3] = ((pcrBase shr 1) and 0xFF).toByte()
        dest[off + 4] = (((pcrBase and 1L) shl 7) or 0x7E).toByte()
        dest[off + 5] = 0
    }

    internal fun psiBootstrap(): ByteArray {
        val out = ByteArrayOutputStream(PACKET * 2)
        out.write(psiPacket(PID_PAT, patSection(), patCc))
        patCc = (patCc + 1) and 0x0F
        out.write(psiPacket(PID_PMT, pmtSection(), pmtCc))
        pmtCc = (pmtCc + 1) and 0x0F
        return out.toByteArray()
    }

    private fun psiPacket(pid: Int, section: ByteArray, cc: Int): ByteArray {
        val pkt = ByteArray(PACKET) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or (pid shr 8)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or (cc and 0x0F)).toByte()
        pkt[4] = 0x00
        System.arraycopy(section, 0, pkt, 5, section.size)
        return pkt
    }

    private fun patSection(): ByteArray {
        val after = byteArrayOf(
            0x00, 0x01,
            0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01,
            (0xE0 or (PID_PMT shr 8)).toByte(), (PID_PMT and 0xFF).toByte()
        )
        return psiSection(0x00, after)
    }

    private fun pmtSection(): ByteArray {
        val video = byteArrayOf(
            0x00, 0x01,
            0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            0x1B,
            (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(),
            0xF0.toByte(), 0x00
        )
        val audio = byteArrayOf(
            0x0F,
            (0xE0 or (PID_AUDIO shr 8)).toByte(), (PID_AUDIO and 0xFF).toByte(),
            0xF0.toByte(), 0x00
        )
        return psiSection(0x02, if (includeAudio) video + audio else video)
    }

    private fun psiSection(tableId: Int, afterLength: ByteArray): ByteArray {
        val slen = afterLength.size + 4
        val body = ByteArray(3 + afterLength.size)
        body[0] = tableId.toByte()
        body[1] = (0xB0 or ((slen shr 8) and 0x0F)).toByte()
        body[2] = (slen and 0xFF).toByte()
        System.arraycopy(afterLength, 0, body, 3, afterLength.size)
        val crc = mpegCrc(body)
        val out = ByteArray(body.size + 4)
        System.arraycopy(body, 0, out, 0, body.size)
        out[body.size] = ((crc ushr 24) and 0xFF).toByte()
        out[body.size + 1] = ((crc ushr 16) and 0xFF).toByte()
        out[body.size + 2] = ((crc ushr 8) and 0xFF).toByte()
        out[body.size + 3] = (crc and 0xFF).toByte()
        return out
    }
}
