package com.mmhw.csvtv

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * One ExoPlayer builder for catalog [PlaybackFragment] and Receive.
 * Buffer sizes stay per-caller (IPTV vs live screen).
 */
@UnstableApi
object AppPlayer {
    data class Buffers(
        val minMs: Int,
        val maxMs: Int,
        val playbackMs: Int,
        val rebufferMs: Int,
        val targetBytes: Int? = null,
        val prioritizeTimeOverSize: Boolean = true
    )

    class Session(
        val player: ExoPlayer,
        val trackSelector: DefaultTrackSelector
    )

    fun create(
        context: Context,
        buffers: Buffers,
        preferSoftware: Boolean = false,
        dropEmulatorCodecs: Boolean = false
    ): Session {
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setForceHighestSupportedBitrate(false)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(false)
                .setViewportSizeToPhysicalDisplaySize(context, true)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
        }
        val codecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            DecoderRanking.rank(
                MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder),
                preferSoftware = preferSoftware,
                dropEmulatorCodecs = dropEmulatorCodecs
            )
        }
        val renderers = DefaultRenderersFactory(context)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        logFfmpegOnce()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                buffers.minMs,
                buffers.maxMs,
                buffers.playbackMs,
                buffers.rebufferMs
            )
            .apply {
                buffers.targetBytes?.let { setTargetBufferBytes(it) }
            }
            .setPrioritizeTimeOverSizeThresholds(buffers.prioritizeTimeOverSize)
            .build()
        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                val audio = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audio, true)
                volume = 1f
                setHandleAudioBecomingNoisy(true)
            }
        return Session(player, trackSelector)
    }

    @Volatile
    private var ffmpegLogged = false

    private fun logFfmpegOnce() {
        if (ffmpegLogged) return
        ffmpegLogged = true
        try {
            val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
            val isAvailable = clazz.getMethod("isAvailable").invoke(null) as Boolean
            val version = if (isAvailable) {
                clazz.getMethod("getVersion").invoke(null) as? String
            } else {
                null
            }
            val supportsMp2 = if (isAvailable) {
                clazz.getMethod("supportsFormat", String::class.java)
                    .invoke(null, MimeTypes.AUDIO_MPEG_L2) as Boolean
            } else {
                false
            }
            Log.i(
                TAG,
                "FFmpeg extension: available=$isAvailable version=$version supportsMpegL2=$supportsMp2"
            )
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "FFmpeg extension not on classpath — MPEG-L2/MP2 audio will be silent")
        } catch (e: Exception) {
            Log.w(TAG, "FFmpeg probe failed: ${e.message}")
        }
    }

    private const val TAG = "AppPlayer"
}
