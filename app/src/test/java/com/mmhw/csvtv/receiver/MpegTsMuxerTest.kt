package com.mmhw.csvtv.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpegTsMuxerTest {
    @Test
    fun packetsAre188AndStartWithSync() {
        val mux = MpegTsMuxer()
        val au = MpegTsMuxer.audNal() + ByteArray(800) { 0x41 }
        val ts = mux.muxVideo(au, ptsUs = 1_000_000, keyframe = true)
        assertEquals(0, ts.size % MpegTsMuxer.PACKET)
        assertTrue(ts.size >= MpegTsMuxer.PACKET * 3)
        var i = 0
        while (i < ts.size) {
            assertEquals(0x47, ts[i].toInt() and 0xff)
            i += MpegTsMuxer.PACKET
        }
    }

    @Test
    fun patCrcMatchesKnownVector() {
        val after = byteArrayOf(
            0x00, 0x01,
            0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01,
            0xF0.toByte(), 0x00
        )
        val slen = after.size + 4
        val body = ByteArray(3 + after.size)
        body[0] = 0x00
        body[1] = (0xB0 or ((slen shr 8) and 0x0F)).toByte()
        body[2] = (slen and 0xFF).toByte()
        System.arraycopy(after, 0, body, 3, after.size)
        val crc = MpegTsMuxer.mpegCrc(body)
        assertEquals(0x2AB104B2, crc)
    }

    @Test
    fun audioPacketsAre188() {
        val mux = MpegTsMuxer()
        val adts = MpegTsMuxer.adtsFrame(ByteArray(200) { 1 })
        val ts = mux.muxAudio(adts, ptsUs = 0)
        assertEquals(0, ts.size % MpegTsMuxer.PACKET)
        assertEquals(0x47, ts[0].toInt() and 0xff)
    }

    @Test
    fun annexBPassthroughAndAvccConvert() {
        val annex = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        assertTrue(MpegTsMuxer.toAnnexB(annex).contentEquals(annex))
        val avcc = byteArrayOf(0, 0, 0, 2, 0x67, 0x42)
        val out = MpegTsMuxer.toAnnexB(avcc)
        assertEquals(0x01.toByte(), out[3])
        assertEquals(0x67.toByte(), out[4])
    }

    @Test
    fun avccDecoderConfigToAnnexB() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x0A)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        val avcc = byteArrayOf(
            1, 0x42, 0, 0x0A, 0xFF.toByte(),
            0xE1.toByte(), 0, 4
        ) + sps + byteArrayOf(1, 0, 4) + pps
        val out = MpegTsMuxer.toAnnexB(avcc)
        assertEquals(0, out[0].toInt())
        assertEquals(0, out[1].toInt())
        assertEquals(0, out[2].toInt())
        assertEquals(1, out[3].toInt())
        assertEquals(0x67, out[4].toInt() and 0xff)
        assertTrue(out.size > 8)
    }

    @Test
    fun bootstrapPsiIsTwoPackets() {
        val psi = MpegTsMuxer.bootstrapPsi()
        assertEquals(MpegTsMuxer.PACKET * 2, psi.size)
        assertEquals(0x47, psi[0].toInt() and 0xff)
        assertEquals(0x47, psi[MpegTsMuxer.PACKET].toInt() and 0xff)
    }

    @Test
    fun isIdrDetectsNalType5() {
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x00)
        assertTrue(MpegTsMuxer.isIdr(idr))
        val shortStart = byteArrayOf(0, 0, 1, 0x65)
        assertTrue(MpegTsMuxer.isIdr(shortStart))
        val pSlice = byteArrayOf(0, 0, 0, 1, 0x41, 0x00)
        assertFalse(MpegTsMuxer.isIdr(pSlice))
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        assertFalse(MpegTsMuxer.isIdr(sps))
    }

    @Test
    fun videoOnlyPmtOmitsAacStream() {
        val withAudio = MpegTsMuxer.bootstrapPsi(includeAudio = true)
        val videoOnly = MpegTsMuxer.bootstrapPsi(includeAudio = false)
        fun pmtSectionLen(psi: ByteArray): Int {
            val pmt = MpegTsMuxer.PACKET
            return ((psi[pmt + 6].toInt() and 0x0F) shl 8) or (psi[pmt + 7].toInt() and 0xFF)
        }
        assertTrue(pmtSectionLen(withAudio) > pmtSectionLen(videoOnly))
        val pmt = videoOnly.copyOfRange(MpegTsMuxer.PACKET, videoOnly.size)
        assertTrue(pmt.contains(0x1B.toByte()))
        val videoEs = pmt.indexOf(0x1B.toByte())
        assertTrue(videoEs >= 0)
        val afterVideoEs = videoEs + 5
        assertTrue(afterVideoEs >= pmt.size || pmt[afterVideoEs] != 0x0F.toByte())
    }
}
