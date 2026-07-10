package com.mmhw.csvtv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.PorterDuff
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@UnstableApi
class PlaybackFragment : Fragment() {
    private var player: ExoPlayer? = null
    private var loadingIndicator: ProgressBar? = null
    private var playerView: PlayerView? = null
    private var errorText: TextView? = null
    private var errorTitle: TextView? = null
    private var errorContainer: View? = null
    private var errorIcon: ImageView? = null
    private var audioOnlyOverlay: View? = null
    private var statusOverlay: View? = null
    private var statusText: TextView? = null
    private var playbackPosition: Long = 0
    private var currentMediaItem: MediaItem? = null
    private var currentSurface: Any? = null
    private var resolvedUrl: String? = null
    private var httpDataSourceFactory: OkHttpDataSource.Factory? = null
    private var trackSelector: DefaultTrackSelector? = null

    /**
     * Decoder strategy:
     * 0 = prefer real hardware (default on devices)
     * 1 = prefer stable software (c2.android.* / omx.google.*) — used after goldfish/HW glitches
     */
    private var decoderMode = DECODER_MODE_HARDWARE
    private var decoderRetryCount = 0
    private val maxDecoderRetries = 2
    /** After a mid-stream goldfish/queue-timeout crash, permanently prefer non-emulator decoders. */
    private var excludeEmulatorDecoders = false

    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelayMs = 3000L
    private val handler = Handler(Looper.getMainLooper())

    // Defaults; re-tuned at runtime from device RAM (4K buffers of 60–120s OOM Fire TV sticks)
    private var minBufferMs = 20000
    private var maxBufferMs = 45000
    private var bufferForPlaybackMs = 2500
    private var bufferForPlaybackAfterRebufferMs = 5000
    private var targetBufferBytes = 32 * 1024 * 1024
    private var isLowRamDevice = false
    private var isEmulator = false

    private var isFirstFrameRendered = false
    private var isStallRestarting = false
    private var isShowingFatalError = false
    /** Soft chip visible during reconnect/retry; must not be cleared on BUFFERING. */
    private var isShowingStatus = false
    private var consecutiveStallRestarts = 0
    private val maxStallRestartsBeforeFullReinit = 2

    // Stall detector: recovers when video freezes or buffers infinitely
    private var lastVideoPosition: Long = 0
    private var stallChecksWithoutProgress = 0
    private val STALL_CHECK_INTERVAL_MS = 2500L
    private val STALL_THRESHOLD = 8 // ~20s of no progress (less thrashy on 4K live)
    private val stallCheckRunnable = Runnable { checkForVideoStall() }

    companion object {
        private const val TAG = "PlaybackFragment"
        private const val DECODER_MODE_HARDWARE = 0
        private const val DECODER_MODE_SOFTWARE = 1
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val urlFromArgs = arguments?.getString("video_url") ?: return
        playerView = view.findViewById(R.id.player_view)
        errorText = view.findViewById(R.id.error_text)
        errorTitle = view.findViewById(R.id.error_title)
        errorContainer = view.findViewById(R.id.error_container)
        errorIcon = view.findViewById(R.id.error_icon)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        audioOnlyOverlay = view.findViewById(R.id.audio_only_overlay)
        statusOverlay = view.findViewById(R.id.status_overlay)
        statusText = view.findViewById(R.id.status_text)

        // Soft rose accent — matches error chip, not harsh solid red
        errorIcon?.setColorFilter(0xFFFF8A80.toInt(), PorterDuff.Mode.SRC_IN)
        hideStatus()

        (loadingIndicator?.layoutParams as? RelativeLayout.LayoutParams)?.apply {
            addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
        }

        // Keep PlayerView visible so the video surface is created once and stays alive.
        // Hiding it (GONE) tears down SurfaceView and causes decoder reconnect + detachBuffer noise.
        loadingIndicator?.visibility = View.VISIBLE
        playerView?.visibility = View.VISIBLE
        playerView?.alpha = 1f
        hideError()
        isShowingFatalError = false

        playerView?.useController = false
        playerView?.keepScreenOn = true

        configureBuffersForDevice()
        isEmulator = isRunningOnEmulator()
        // Emulator "hardware" (goldfish) is flaky for live streams — start on stable SW decoders.
        if (isEmulator) {
            decoderMode = DECODER_MODE_SOFTWARE
            excludeEmulatorDecoders = true
            Log.i(TAG, "Emulator detected — preferring c2.android.* decoders over goldfish")
        }

        playbackPosition = savedInstanceState?.getLong("playback_position", 0) ?: 0
        resolvedUrl = urlFromArgs
        initializePlayer(urlFromArgs)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("playback_position", player?.currentPosition ?: playbackPosition)
        Log.d(TAG, "Saving playback position: ${player?.currentPosition ?: playbackPosition}")
    }

    /**
     * 4K@15Mbps * 120s ≈ 225MB of buffered media — enough to LMK-kill Fire TV sticks.
     * Cap time and absolute bytes based on total RAM.
     */
    private fun configureBuffersForDevice() {
        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        isLowRamDevice = am.isLowRamDevice || totalMb < 2800

        if (isLowRamDevice) {
            // Fire TV Stick / Plus class devices
            minBufferMs = 12000
            maxBufferMs = 28000
            bufferForPlaybackMs = 2000
            bufferForPlaybackAfterRebufferMs = 4000
            targetBufferBytes = 18 * 1024 * 1024
        } else if (totalMb < 4096) {
            minBufferMs = 18000
            maxBufferMs = 40000
            bufferForPlaybackMs = 2500
            bufferForPlaybackAfterRebufferMs = 5000
            targetBufferBytes = 28 * 1024 * 1024
        } else {
            minBufferMs = 25000
            maxBufferMs = 55000
            bufferForPlaybackMs = 3000
            bufferForPlaybackAfterRebufferMs = 6000
            targetBufferBytes = 48 * 1024 * 1024
        }

        Log.i(
            TAG,
            "Buffer config: totalRam=${totalMb}MB lowRam=$isLowRamDevice " +
                "min=${minBufferMs}ms max=${maxBufferMs}ms targetBytes=${targetBufferBytes / (1024 * 1024)}MB"
        )
    }

    private fun initializePlayer(urlToPlay: String) {
        val newSurface = playerView?.videoSurfaceView
        if (player != null && currentSurface == newSurface && player?.playbackState != Player.STATE_ENDED) {
            player?.seekTo(playbackPosition)
            player?.playWhenReady = true
            Log.d(TAG, "Reusing existing player, seeking to $playbackPosition")
            return
        }
        currentSurface = newSurface

        hideError()
        isFirstFrameRendered = false
        // Preserve soft reconnect UI across full re-init (status chip must stay up).
        if (isShowingStatus) {
            isStallRestarting = true
        } else {
            isShowingFatalError = false
            isStallRestarting = false
        }

        if (httpDataSourceFactory == null) {
            val okHttpClient = OkHttpClient.Builder()
                .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .sslSocketFactory(createUnsafeSslContext().socketFactory, createUnsafeTrustManager())
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("ExoPlayer-CSVTV")
        }

        releasePlayerSafely()

        trackSelector = DefaultTrackSelector(requireContext()).apply {
            // Prefer a stable adaptive ladder over always-max 4K when the playlist offers choices.
            // forceHighestSupportedBitrate=false lets bandwidth estimator pick a sustainable rung.
            parameters = buildUponParameters()
                .setForceHighestSupportedBitrate(false)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(false)
                .setViewportSizeToPhysicalDisplaySize(requireContext(), /* orientationMayChange= */ true)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                // Never disable audio — some streams only expose audio after the first period.
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, /* disabled= */ false)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, /* disabled= */ false)
                .build()
        }

        val preferSoftware = decoderMode == DECODER_MODE_SOFTWARE
        val codecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            rankDecoders(
                MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder),
                preferSoftware = preferSoftware,
                dropEmulatorCodecs = excludeEmulatorDecoders
            )
        }

        // EXTENSION_RENDERER_MODE_ON: use MediaCodec first; fall back to FFmpeg for codecs
        // Android cannot decode natively (notably audio/mpeg-L2 / MP2 common in broadcast TS).
        val renderersFactory = DefaultRenderersFactory(requireContext())
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        logFfmpegAvailability()

        // prioritizeTimeOverSizeThresholds=false is critical: otherwise ExoPlayer ignores
        // targetBufferBytes and keeps filling time-based windows for 4K → OOM on Fire TV.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build().apply {
                // Route audio as movie/media so TV / HDMI / emulator host pick up the stream.
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                volume = 1f
                setHandleAudioBecomingNoisy(true)

                val mimeType = arguments?.getString("mime_type")
                val format = Utils.determineVideoFormat(urlToPlay, mimeType)
                val mediaItem = MediaItem.Builder()
                    .setUri(urlToPlay)
                    .apply {
                        if (format == "M3U8") {
                            setMimeType("application/x-mpegURL")
                        } else if (!mimeType.isNullOrEmpty()) {
                            setMimeType(mimeType)
                        }
                    }
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            // Slightly larger target offset reduces decoder thrash on jittery 4K live
                            .setTargetOffsetMs(if (isLowRamDevice) 5000 else 4000)
                            .setMaxPlaybackSpeed(1.02f)
                            .setMinPlaybackSpeed(0.98f)
                            .build()
                    )
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Video Stream").build())
                    .build()
                this@PlaybackFragment.currentMediaItem = mediaItem

                val mediaSource = if (urlToPlay.startsWith("rtmp://")) {
                    val rtmpDataSourceFactory = RtmpDataSource.Factory()
                    DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(mediaItem)
                } else {
                    DefaultMediaSourceFactory(httpDataSourceFactory!!).createMediaSource(mediaItem)
                }

                setMediaSource(mediaSource)
                prepare()
                // Only seek for non-live / VOD; seeking a live window to a stale offset can drop A/V.
                if (playbackPosition > 0) {
                    seekTo(playbackPosition)
                }
                playWhenReady = false

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        handler.removeCallbacks(stallCheckRunnable)
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                // Never GONE the PlayerView here — surface must stay connected.
                                // During reconnect, keep the soft status chip and skip the big spinner
                                // so the last frame stays visible instead of flashing.
                                if (isStallRestarting || isShowingStatus) {
                                    loadingIndicator?.visibility = View.GONE
                                } else {
                                    loadingIndicator?.alpha = 1f
                                    loadingIndicator?.visibility = View.VISIBLE
                                }
                                if (!isShowingFatalError) {
                                    // Don't hideStatus() here — retry/reconnect messages must stay.
                                    hideError()
                                    playerView?.visibility = View.VISIBLE
                                    playerView?.alpha = 1f
                                }
                                audioOnlyOverlay?.visibility = View.GONE
                                playerView?.useController = false
                                playerView?.hideController()
                                Log.d(TAG, "Playback state changed: BUFFERING")
                            }
                            Player.STATE_READY -> {
                                if (!isShowingFatalError) hideError()
                                hideStatus()
                                retryCount = 0
                                decoderRetryCount = 0
                                consecutiveStallRestarts = 0
                                val wasStallRestarting = isStallRestarting
                                isStallRestarting = false

                                if (!wasStallRestarting) {
                                    loadingIndicator?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                                        loadingIndicator?.visibility = View.GONE
                                        loadingIndicator?.alpha = 1f
                                    }?.start()
                                } else {
                                    loadingIndicator?.visibility = View.GONE
                                }

                                val isAudioOnly = player?.videoFormat == null
                                audioOnlyOverlay?.visibility = if (isAudioOnly) View.VISIBLE else View.GONE

                                logPlaybackFormats()

                                // Keep surface alive for video; only cover it for true audio-only.
                                if (!isShowingFatalError) {
                                    playerView?.visibility = View.VISIBLE
                                    playerView?.alpha = 1f
                                }

                                // Re-assert audible output every time we become READY (after recoveries).
                                volume = 1f
                                playerView?.useController = true
                                playWhenReady = true
                                Log.d(TAG, "Playback state changed: READY volume=$volume muted=$isDeviceMuted")

                                if (isFirstFrameRendered && player?.videoFormat != null) {
                                    startStallDetection()
                                }

                                val channelCount = player?.audioFormat?.channelCount
                                val audioChannels = when (channelCount) {
                                    1 -> "Mono"
                                    2 -> "Stereo"
                                    6 -> "5.1"
                                    else -> if (channelCount != null && channelCount > 0) "${channelCount}ch" else null
                                }

                                if (player?.audioFormat == null && player?.videoFormat != null) {
                                    val unsupportedAudio = findUnsupportedAudioDescription(player)
                                    if (unsupportedAudio != null) {
                                        Log.w(
                                            TAG,
                                            "Audio present but not playable: $unsupportedAudio " +
                                                "(needs FFmpeg extension or device decoder)"
                                        )
                                    } else {
                                        Log.w(
                                            TAG,
                                            "No audio track selected (video-only stream, missing audio, " +
                                                "or decoder not ready)."
                                        )
                                    }
                                }

                                resolvedUrl?.let { url ->
                                    context?.let { ctx ->
                                        Utils.saveAudioMetadata(ctx, url, isAudioOnly, audioChannels)
                                    }
                                }
                            }
                            Player.STATE_ENDED -> {
                                loadingIndicator?.visibility = View.GONE
                                if (!isShowingFatalError) hideError()
                                hideStatus()
                                audioOnlyOverlay?.visibility = View.GONE
                                if (!isShowingFatalError) {
                                    playerView?.visibility = View.VISIBLE
                                    playerView?.alpha = 1f
                                }
                                playerView?.useController = true
                                Log.d(TAG, "Playback state changed: ENDED")
                            }
                            Player.STATE_IDLE -> {
                                // Stay visible during brief IDLE between stop/prepare recovery.
                                // Prefer status chip over big spinner when reconnecting.
                                if (!isShowingStatus && !isStallRestarting) {
                                    loadingIndicator?.visibility = View.VISIBLE
                                } else {
                                    loadingIndicator?.visibility = View.GONE
                                }
                                if (!isShowingFatalError) hideError()
                                audioOnlyOverlay?.visibility = View.GONE
                                if (!isShowingFatalError) {
                                    playerView?.visibility = View.VISIBLE
                                    playerView?.alpha = 1f
                                }
                                playerView?.useController = true
                                Log.d(TAG, "Playback state changed: IDLE")
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // Keep surface + last frame; use soft status for recovery, not red fatal panel.
                        loadingIndicator?.visibility = View.GONE
                        if (!isShowingFatalError) {
                            playerView?.visibility = View.VISIBLE
                            playerView?.alpha = 1f
                        }
                        handler.removeCallbacks(stallCheckRunnable)
                        isStallRestarting = false

                        val isVideoError = isVideoDecoderError(error)
                        val isHighRes = isLikely4kOrHdrStream(error)
                        val isTransient = isTransientDecoderFailure(error)
                        val usedEmulatorCodec = errorMentionsEmulatorCodec(error)

                        Log.e(
                            TAG,
                            "Player error code=${error.errorCode} highRes=$isHighRes " +
                                "transient=$isTransient emulatorCodec=$usedEmulatorCodec " +
                                "decoderMode=$decoderMode msg=${error.message}",
                            error
                        )

                        if (isVideoError) {
                            if (usedEmulatorCodec) {
                                excludeEmulatorDecoders = true
                            }

                            // Goldfish/queue-timeout on an otherwise supported format (e.g. 1080p AVC):
                            // skip more HW retries that would re-select goldfish; jump to stable SW.
                            if (isTransient && !isHighRes && decoderMode != DECODER_MODE_SOFTWARE) {
                                decoderMode = DECODER_MODE_SOFTWARE
                                excludeEmulatorDecoders = true
                                Log.w(TAG, "Transient decoder crash on supported format. Switching to software decoders.", error)
                                showStatus("Switching decoder…")
                                schedulePlayerReinit(900L)
                                return
                            }

                            // Clean re-init (same mode, but goldfish already deprioritized/excluded).
                            if (isTransient && decoderRetryCount < maxDecoderRetries) {
                                decoderRetryCount++
                                showStatus("Recovering video… ($decoderRetryCount/$maxDecoderRetries)")
                                schedulePlayerReinit(1000L)
                                return
                            }

                            // HW path exhausted for non-4K: try software once.
                            if (decoderMode == DECODER_MODE_HARDWARE && !isHighRes) {
                                decoderMode = DECODER_MODE_SOFTWARE
                                excludeEmulatorDecoders = true
                                Log.w(TAG, "HW decoder failed on non-4K stream. Trying software decoder.", error)
                                showStatus("Trying alternate decoder…")
                                schedulePlayerReinit(1000L)
                                return
                            }

                            // Unsupported / unrecoverable
                            isShowingFatalError = true
                            val detail = when {
                                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                                    error.message?.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true) == true ->
                                    "This device can’t decode 4K / HDR / HEVC Main10."
                                isHighRes ->
                                    "4K/HDR stream failed. Try a lower-bitrate source if available."
                                isEmulator ->
                                    "Emulator decoder failed. Try a Fire TV / Android TV device."
                                else ->
                                    "Stream playback failed on this device."
                            }
                            showFatalError(
                                title = "Unable to play",
                                message = detail
                            )
                            playerView?.useController = false
                            Log.e(TAG, "Fatal video error: ${error.message}")

                            releasePlayerSafely()
                            player = null

                            handler.postDelayed({
                                if (isAdded && !isDetached) {
                                    parentFragmentManager.popBackStack()
                                }
                            }, 6000L)
                            return
                        }

                        if (retryCount < maxRetries) {
                            retryCount++
                            showStatus("Reconnecting… ($retryCount/$maxRetries)")
                            Log.d(TAG, "Player error, retrying ($retryCount/$maxRetries): ${error.message}")
                            handler.postDelayed({
                                if (isAdded) restartPlayer(fullReinit = true)
                            }, retryDelayMs)
                        } else {
                            isShowingFatalError = true
                            showFatalError(
                                title = "Playback failed",
                                message = error.message?.take(160) ?: "Unknown error"
                            )
                            playerView?.useController = false
                            Log.e(TAG, "Failed to play stream after $maxRetries attempts: ${error.message}")

                            releasePlayerSafely()
                            player = null

                            handler.postDelayed({
                                if (isAdded && !isDetached) {
                                    parentFragmentManager.popBackStack()
                                }
                            }, 6000L)
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        isFirstFrameRendered = true
                        playbackPosition = player?.currentPosition ?: 0
                        Log.d(TAG, "First frame rendered at position: $playbackPosition")
                        startStallDetection()
                    }
                })
            }

        playerView?.player = player
        Log.d(TAG, "Player initialized with URL: $urlToPlay decoderMode=$decoderMode")
    }

    private fun schedulePlayerReinit(delayMs: Long) {
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            releasePlayerSafely()
            player = null
            currentSurface = null
            resolvedUrl?.let { initializePlayer(it) }
        }, delayMs)
    }

    /**
     * Prefer real device HW, then Google/Android software codecs.
     * Emulator goldfish/ranchu codecs report as "hardware" but frequently hit
     * queue timeouts (Error 0x80000000) on live streams — always rank them last,
     * or drop them entirely after a goldfish failure.
     */
    private fun rankDecoders(
        infos: List<MediaCodecInfo>,
        preferSoftware: Boolean,
        dropEmulatorCodecs: Boolean
    ): MutableList<MediaCodecInfo> {
        fun isEmulatorCodec(info: MediaCodecInfo): Boolean {
            val n = info.name.lowercase()
            return n.contains("goldfish") || n.contains("ranchu") || n.contains("emulator")
        }
        fun isGoogleSoftware(info: MediaCodecInfo): Boolean {
            val n = info.name.lowercase()
            return n.contains("c2.android") || n.contains("omx.google") || n.startsWith("c2.google")
        }

        val filtered = if (dropEmulatorCodecs) {
            val without = infos.filterNot { isEmulatorCodec(it) }
            // Keep goldfish only if it is the only option left.
            if (without.isNotEmpty()) without else infos
        } else {
            infos
        }

        return filtered.sortedWith(
            compareBy<MediaCodecInfo> { info ->
                val emu = isEmulatorCodec(info)
                val googleSw = isGoogleSoftware(info)
                val realHw = info.hardwareAccelerated && !emu
                when {
                    preferSoftware && googleSw -> 0
                    preferSoftware && !realHw && !emu -> 1
                    !preferSoftware && realHw -> 0
                    !preferSoftware && googleSw -> 1
                    realHw -> 2
                    googleSw -> 3
                    emu -> 90
                    else -> 50
                }
            }.thenBy { it.name }
        ).toMutableList().also { ranked ->
            if (Log.isLoggable(TAG, Log.DEBUG) && ranked.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Decoder order (preferSw=$preferSoftware dropEmu=$dropEmulatorCodecs): " +
                        ranked.joinToString { it.name }
                )
            }
        }
    }

    private fun isRunningOnEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val product = Build.PRODUCT
        val hardware = Build.HARDWARE
        val manufacturer = Build.MANUFACTURER
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            manufacturer.contains("Genymotion") ||
            product.contains("sdk") ||
            product.contains("google_sdk") ||
            product.contains("sdk_google") ||
            product.contains("vbox86p") ||
            product.contains("emulator") ||
            product.contains("simulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
    }

    private fun errorTextBlob(error: PlaybackException): String {
        return buildString {
            append(error.message.orEmpty())
            append(' ')
            var c = error.cause
            var depth = 0
            while (c != null && depth < 4) {
                append(c.message.orEmpty())
                append(' ')
                append(c.javaClass.simpleName)
                append(' ')
                c = c.cause
                depth++
            }
        }
    }

    private fun isVideoDecoderError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
        ) {
            return true
        }
        val msg = errorTextBlob(error)
        return msg.contains("exceeds", ignoreCase = true) ||
            msg.contains("unsupported", ignoreCase = true) ||
            msg.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true) ||
            msg.contains("MediaCodecVideoRenderer", ignoreCase = true) ||
            msg.contains("Decoder failed", ignoreCase = true) ||
            msg.contains("CodecException", ignoreCase = true)
    }

    /**
     * Mid-stream codec crash on a format the device already accepted
     * (queue timeout / 0x80000000 / goldfish) — recoverable by re-init.
     */
    private fun isTransientDecoderFailure(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        ) {
            return false
        }
        val msg = errorTextBlob(error)
        if (msg.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true) ||
            msg.contains("format_supported=NO", ignoreCase = true)
        ) {
            return false
        }
        // Explicitly supported but decoder died mid-stream
        if (msg.contains("format_supported=YES", ignoreCase = true)) return true
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
        ) {
            return true
        }
        return msg.contains("0x80000000") ||
            msg.contains("exceeded timeout", ignoreCase = true) ||
            msg.contains("queue exceeded", ignoreCase = true) ||
            errorMentionsEmulatorCodec(error)
    }

    private fun errorMentionsEmulatorCodec(error: PlaybackException): Boolean {
        val msg = errorTextBlob(error).lowercase()
        return msg.contains("goldfish") || msg.contains("ranchu") || msg.contains("emulator")
    }

    /**
     * True only for real 4K / HDR-class streams where software decode will OOM Fire TV.
     * 1080p H.264 is NOT treated as high-res — SW fallback is fine there (and needed on emulator).
     */
    private fun isLikely4kOrHdrStream(error: PlaybackException): Boolean {
        val vf = player?.videoFormat
        if (vf != null) {
            if (vf.height >= 2160 || vf.width >= 3840) return true
            val color = vf.colorInfo
            if (color != null) {
                // ST2084 / HLG transfer or BT2020 usually means HDR10/HLG
                val transfer = color.colorTransfer
                val space = color.colorSpace
                if (transfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 ||
                    transfer == androidx.media3.common.C.COLOR_TRANSFER_HLG ||
                    space == androidx.media3.common.C.COLOR_SPACE_BT2020
                ) {
                    return true
                }
            }
            if (vf.sampleMimeType?.contains("hevc", ignoreCase = true) == true &&
                vf.bitrate >= 12_000_000 &&
                (vf.height >= 1080 || vf.width >= 1920)
            ) {
                return true
            }
        }
        val blob = errorTextBlob(error)
        return blob.contains("3840") ||
            blob.contains("2160") ||
            blob.contains("ST2084", ignoreCase = true) ||
            blob.contains("BT2020", ignoreCase = true) ||
            blob.contains("L153", ignoreCase = true) ||
            blob.contains("10bit", ignoreCase = true) ||
            (blob.contains("hvc1", ignoreCase = true) && blob.contains("L15", ignoreCase = true))
    }

    private fun logFfmpegAvailability() {
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
                "FFmpeg extension: available=$isAvailable version=$version " +
                    "supportsMpegL2=$supportsMp2"
            )
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "FFmpeg extension not on classpath — MPEG-L2/MP2 audio will be silent")
        } catch (e: Exception) {
            Log.w(TAG, "FFmpeg probe failed: ${e.message}")
        }
    }

    /** Human-readable note for audio tracks that exist but no renderer can play. */
    private fun findUnsupportedAudioDescription(player: Player?): String? {
        val p = player ?: return null
        try {
            for (i in 0 until p.currentTracks.groups.size) {
                val g = p.currentTracks.groups[i]
                if (g.type != C.TRACK_TYPE_AUDIO) continue
                if (g.isSupported) continue
                val f = if (g.length > 0) g.getTrackFormat(0) else null
                return "${f?.sampleMimeType ?: "audio"} codecs=${f?.codecs} ch=${f?.channelCount}"
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun logPlaybackFormats() {
        val p = player ?: return
        val v = p.videoFormat
        if (v != null) {
            Log.i(
                TAG,
                "Playing video: ${v.width}x${v.height}@${v.frameRate} " +
                    "mime=${v.sampleMimeType} codecs=${v.codecs} bitrate=${v.bitrate} " +
                    "color=${v.colorInfo}"
            )
        } else {
            Log.i(TAG, "Playing video: (none)")
        }

        val a = p.audioFormat
        if (a != null) {
            Log.i(
                TAG,
                "Playing audio: mime=${a.sampleMimeType} codecs=${a.codecs} " +
                    "channels=${a.channelCount} sampleRate=${a.sampleRate} " +
                    "bitrate=${a.bitrate} language=${a.language}"
            )
        } else {
            Log.w(TAG, "Playing audio: (none selected)")
        }

        // Dump available track groups so we can tell "no audio in stream" vs "not selected".
        try {
            val groups = p.currentTracks.groups
            if (groups.isEmpty()) {
                Log.w(TAG, "Track list empty")
            } else {
                for (i in 0 until groups.size) {
                    val g = groups[i]
                    val type = when (g.type) {
                        C.TRACK_TYPE_AUDIO -> "audio"
                        C.TRACK_TYPE_VIDEO -> "video"
                        C.TRACK_TYPE_TEXT -> "text"
                        else -> "other(${g.type})"
                    }
                    val formats = buildString {
                        for (j in 0 until g.length) {
                            val f = g.getTrackFormat(j)
                            if (j > 0) append(", ")
                            append(f.sampleMimeType ?: "?")
                            append('/')
                            append(f.codecs ?: "-")
                            if (f.channelCount != Format.NO_VALUE && f.channelCount > 0) {
                                append(" ch=${f.channelCount}")
                            }
                            if (f.width != Format.NO_VALUE) {
                                append(" ${f.width}x${f.height}")
                            }
                        }
                    }
                    Log.i(
                        TAG,
                        "TrackGroup[$i] type=$type selected=${g.isSelected} " +
                            "supported=${g.isSupported} formats=[$formats]"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log tracks: ${e.message}")
        }
    }

    private fun releasePlayerSafely() {
        val p = player ?: return
        try {
            playerView?.player = null
        } catch (_: Exception) {
        }
        try {
            p.playWhenReady = false
            p.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop() after codec failure (expected): ${e.message}")
        }
        try {
            p.release()
        } catch (e: Exception) {
            Log.w(TAG, "release() after codec failure (expected): ${e.message}")
        }
        player = null
    }

    private fun restartPlayer(fullReinit: Boolean = false) {
        handler.removeCallbacks(stallCheckRunnable)
        val p = player

        if (!isShowingStatus) {
            showStatus("Reconnecting stream…")
        }

        if (fullReinit || p == null || consecutiveStallRestarts >= maxStallRestartsBeforeFullReinit) {
            consecutiveStallRestarts = 0
            isStallRestarting = true
            releasePlayerSafely()
            currentSurface = null
            resolvedUrl?.let { initializePlayer(it) }
            return
        }

        playbackPosition = p.currentPosition
        lastVideoPosition = 0
        stallChecksWithoutProgress = 0
        isStallRestarting = true
        consecutiveStallRestarts++

        try {
            p.stop()
            p.clearMediaItems()

            val urlToPlay = resolvedUrl ?: return
            val mimeType = arguments?.getString("mime_type")
            val format = Utils.determineVideoFormat(urlToPlay, mimeType)
            val mediaItem = MediaItem.Builder()
                .setUri(urlToPlay)
                .apply {
                    if (format == "M3U8") {
                        setMimeType("application/x-mpegURL")
                    } else if (!mimeType.isNullOrEmpty()) {
                        setMimeType(mimeType)
                    }
                }
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(if (isLowRamDevice) 5000 else 4000)
                        .setMaxPlaybackSpeed(1.02f)
                        .setMinPlaybackSpeed(0.98f)
                        .build()
                )
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Video Stream").build())
                .build()
            currentMediaItem = mediaItem

            val mediaSource = if (urlToPlay.startsWith("rtmp://")) {
                val rtmpDataSourceFactory = RtmpDataSource.Factory()
                DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(mediaItem)
            } else {
                DefaultMediaSourceFactory(httpDataSourceFactory!!).createMediaSource(mediaItem)
            }

            p.setMediaSource(mediaSource)
            p.prepare()
            // For live, prefer joining the live edge rather than a stale position after a stall.
            if (p.isCurrentMediaItemLive) {
                p.seekToDefaultPosition()
            } else {
                p.seekTo(playbackPosition)
            }
            p.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Lightweight restart failed, doing full reinit", e)
            releasePlayerSafely()
            currentSurface = null
            resolvedUrl?.let { initializePlayer(it) }
        }
    }

    /**
     * Soft translucent chip over the video for recoverable reconnect/retry.
     * Survives BUFFERING so it doesn't flash away.
     */
    private fun showStatus(message: String) {
        isShowingStatus = true
        // Fatal and status share the same bottom slot — only one chip at a time.
        hideErrorImmediate()
        statusText?.text = message
        fadeInChip(statusOverlay)
        loadingIndicator?.visibility = View.GONE
        Log.d(TAG, "Status: $message")
    }

    private fun hideStatus() {
        if (!isShowingStatus && statusOverlay?.visibility != View.VISIBLE) return
        isShowingStatus = false
        fadeOutChip(statusOverlay)
    }

    /**
     * Soft fatal chip (same placement/animation as status) — rose accent, not a harsh red panel.
     */
    private fun showFatalError(title: String, message: String) {
        isShowingFatalError = true
        isShowingStatus = false
        fadeOutChip(statusOverlay, immediate = true)
        errorTitle?.text = title
        errorText?.text = message
        loadingIndicator?.visibility = View.GONE
        // Keep surface area black/calm; chip floats bottom-center like status.
        playerView?.visibility = View.VISIBLE
        playerView?.alpha = 1f
        fadeInChip(errorContainer)
        Log.d(TAG, "Fatal: $title — $message")
    }

    private fun hideError() {
        fadeOutChip(errorContainer)
    }

    private fun hideErrorImmediate() {
        errorContainer?.animate()?.cancel()
        errorContainer?.visibility = View.GONE
        errorContainer?.alpha = 1f
    }

    private fun fadeInChip(chip: View?) {
        val view = chip ?: return
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(180).start()
        } else {
            view.alpha = 1f
        }
    }

    private fun fadeOutChip(chip: View?, immediate: Boolean = false) {
        val view = chip ?: return
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.GONE
            return
        }
        if (immediate) {
            view.visibility = View.GONE
            view.alpha = 1f
            return
        }
        view.animate().alpha(0f).setDuration(200).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }

    private fun createUnsafeSslContext(): SSLContext {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(createUnsafeTrustManager()), SecureRandom())
        return sslContext
    }

    private fun createUnsafeTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    override fun onStart() {
        super.onStart()
        // After power/Home we fully release on onStop — rebuild the player when visible again.
        val url = resolvedUrl
        if (player == null && !url.isNullOrBlank() && view != null && !isShowingFatalError) {
            Log.d(TAG, "onStart: Re-initializing stream after hard stop")
            showStatus("Resuming stream…")
            initializePlayer(url)
            return
        }
        player?.playWhenReady = true
        Log.d(TAG, "onStart: playWhenReady=true (player already active)")
        if (isFirstFrameRendered && player?.playbackState == Player.STATE_READY && player?.videoFormat != null) {
            startStallDetection()
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(stallCheckRunnable)
        player?.let {
            // No paused keep-alive: drop live streams cleanly; only keep VOD offset for resume.
            playbackPosition = if (it.isCurrentMediaItemLive) 0L else it.currentPosition
        }
        // Hard stop — free decoder, buffers, and network. Do not leave a paused stream running.
        releasePlayerSafely()
        currentSurface = null
        isFirstFrameRendered = false
        Log.d(TAG, "onStop: Stream stopped and player released (no pause)")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        playerView?.keepScreenOn = false
        releasePlayerSafely()
        playerView = null
        loadingIndicator = null
        errorText = null
        errorTitle = null
        errorContainer = null
        errorIcon = null
        statusOverlay = null
        statusText = null
        currentSurface = null
        resolvedUrl = null
        currentMediaItem = null
        lastVideoPosition = 0
        stallChecksWithoutProgress = 0
        isStallRestarting = false
        isShowingFatalError = false
        isShowingStatus = false
        consecutiveStallRestarts = 0
        decoderMode = DECODER_MODE_HARDWARE
        decoderRetryCount = 0
        excludeEmulatorDecoders = false
        httpDataSourceFactory = null
        trackSelector = null
        Log.d(TAG, "onDestroyView: Player released and views nullified")
    }

    private fun startStallDetection() {
        handler.removeCallbacks(stallCheckRunnable)
        lastVideoPosition = player?.currentPosition ?: 0
        stallChecksWithoutProgress = 0
        handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
    }

    private fun checkForVideoStall() {
        val p = player
        if (p == null || !p.playWhenReady) {
            stallChecksWithoutProgress = 0
            handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
            return
        }

        val currentPos = p.currentPosition

        if (p.playbackState == Player.STATE_BUFFERING) {
            stallChecksWithoutProgress++
            Log.d(TAG, "Stall check: buffering ($stallChecksWithoutProgress/$STALL_THRESHOLD)")
            if (stallChecksWithoutProgress >= STALL_THRESHOLD) {
                Log.w(TAG, "Video stalled (buffering timeout). Restarting player.")
                showStatus("Stream stalled — reconnecting…")
                restartPlayer(fullReinit = consecutiveStallRestarts >= 1)
                return
            }
        } else if (p.playbackState == Player.STATE_READY) {
            val advanced = currentPos - lastVideoPosition
            if (advanced < 100 && currentPos > 0) {
                stallChecksWithoutProgress++
                Log.d(TAG, "Stall check: frozen position ($stallChecksWithoutProgress/$STALL_THRESHOLD)")
                if (stallChecksWithoutProgress >= STALL_THRESHOLD) {
                    Log.w(TAG, "Video stalled (decoder frozen). Restarting player.")
                    showStatus("Video frozen — recovering…")
                    restartPlayer(fullReinit = true)
                    return
                }
            } else {
                stallChecksWithoutProgress = 0
            }
        } else {
            stallChecksWithoutProgress = 0
        }

        lastVideoPosition = currentPos
        handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
    }
}
