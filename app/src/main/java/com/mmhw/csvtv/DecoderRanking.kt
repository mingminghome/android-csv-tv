package com.mmhw.csvtv

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo

/**
 * Shared MediaCodec order for catalog playback and Receive.
 * Real device hardware first, then Google software; emulator codecs last.
 */
@UnstableApi
object DecoderRanking {
    fun isEmulatorCodec(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("goldfish") || n.contains("ranchu") || n.contains("emulator")
    }

    fun isGoogleSoftware(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("c2.android") || n.contains("omx.google") || n.startsWith("c2.google")
    }

    fun sortKey(name: String, hardwareAccelerated: Boolean, preferSoftware: Boolean): Int {
        val emu = isEmulatorCodec(name)
        val googleSw = isGoogleSoftware(name)
        val realHw = hardwareAccelerated && !emu
        return when {
            preferSoftware && googleSw -> 0
            preferSoftware && !realHw && !emu -> 1
            !preferSoftware && realHw -> 0
            !preferSoftware && googleSw -> 1
            realHw -> 2
            googleSw -> 3
            emu -> 90
            else -> 50
        }
    }

    fun rank(
        infos: List<MediaCodecInfo>,
        preferSoftware: Boolean,
        dropEmulatorCodecs: Boolean
    ): MutableList<MediaCodecInfo> {
        val filtered = if (dropEmulatorCodecs) {
            val without = infos.filterNot { isEmulatorCodec(it.name) }
            if (without.isNotEmpty()) without else infos
        } else {
            infos
        }
        return filtered.sortedWith(
            compareBy<MediaCodecInfo> { info ->
                sortKey(info.name, info.hardwareAccelerated, preferSoftware)
            }.thenBy { it.name }
        ).toMutableList()
    }
}
