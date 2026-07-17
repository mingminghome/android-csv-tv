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
import androidx.activity.OnBackPressedCallback
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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
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
    /** Live ENDED / playlist-stuck recoveries use a separate budget so brief stalls don't burn error retries. */
    private var liveRecoverCount = 0
    private val maxLiveRecovers = 8
    private val liveRecoverDelayMs = 2500L
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Media3 default is 3.5× targetDuration (~14s for 4s segments). IPTV gateways often pause
     * playlist updates longer without being truly dead — raise tolerance before PlaylistStuckException.
     */
    private val hlsPlaylistStuckCoefficient = 7.0

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

    // Stall detector: recovers when video freezes or buffers infinitely.
    // Balanced middle: reconnect faster than the old ~20s wait, but avoid thrashing on
    // live-edge micro-freezes (separate counters + stricter frozen criteria).
    private var lastVideoPosition: Long = 0
    private var bufferingStallChecks = 0
    private var frozenStallChecks = 0
    private var didShowBufferingHint = false
    private val STALL_CHECK_INTERVAL_MS = 2000L
    /** ~10s continuous BUFFERING before soft reconnect. */
    private val BUFFERING_STALL_THRESHOLD = 5
    /** ~14s true freeze (not live-edge jitter) before full reinit. */
    private val FROZEN_STALL_THRESHOLD = 7
    /** Show soft "Buffering…" after ~4s so the UI does not look dead. */
    private val BUFFERING_STATUS_HINT_AFTER = 2
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

        // Single Back → previous surface (WebView session if handed off from a page,
        // otherwise main browse). Do not require hiding controller first.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!isAdded) return
                    try {
                        if (parentFragmentManager.backStackEntryCount > 0 &&
                            !parentFragmentManager.isStateSaved
                        ) {
                            parentFragmentManager.popBackStack()
                        } else {
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Back navigation failed", e)
                    }
                }
            }
        )

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
                // IPTV/CDN segments often stall mid-read; 15s was too aggressive and
                // produced Source error (SocketTimeout) after long BUFFERING with no recovery.
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
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
                val isLikelyLive = isLikelyLiveStream(urlToPlay, format, mimeType)
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

                val mediaSource = createMediaSource(urlToPlay, format, mediaItem)

                setMediaSource(mediaSource)
                prepare()
                // Live/HLS: always join the default/live edge. Seeking a sliding window to a
                // stale offset (or near the end of a short residual window) can yield immediate ENDED.
                if (playbackPosition > 0 && !isLikelyLive) {
                    seekTo(playbackPosition)
                }
                playWhenReady = false

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
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
                                // Critical: do NOT cancel stall detection on BUFFERING.
                                // Previously removeCallbacks(stallCheckRunnable) on every state
                                // change left long rebuffers (60s+) without auto-reconnect until
                                // OkHttp finally threw SocketTimeoutException.
                                if (isFirstFrameRendered && !isShowingFatalError) {
                                    ensureStallDetectionRunning()
                                }
                            }
                            Player.STATE_READY -> {
                                if (!isShowingFatalError) hideError()
                                hideStatus()
                                decoderRetryCount = 0
                                consecutiveStallRestarts = 0
                                val wasStallRestarting = isStallRestarting
                                isStallRestarting = false
                                // Only clear retry budgets after stable play — READY+one-frame+ENDED
                                // must not reset counters (that loop never exhausts maxLiveRecovers).
                                scheduleStablePlaybackCredit()

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
                                handler.removeCallbacks(stallCheckRunnable)
                                loadingIndicator?.visibility = View.GONE
                                if (!isShowingFatalError) hideError()
                                audioOnlyOverlay?.visibility = View.GONE
                                if (!isShowingFatalError) {
                                    playerView?.visibility = View.VISIBLE
                                    playerView?.alpha = 1f
                                }
                                playerView?.useController = true
                                Log.d(TAG, "Playback state changed: ENDED")

                                // IPTV/live HLS often hits ENDED after a stuck playlist or a short
                                // residual window (one frame then stop). Rejoin the live edge.
                                if (!isShowingFatalError && shouldRecoverLiveEnded()) {
                                    scheduleLiveRecover("Stream ended — rejoining…")
                                } else {
                                    hideStatus()
                                }
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
                        handler.removeCallbacks(stablePlaybackCreditRunnable)
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

                        // Playlist stuck / source IO: clear any live seek offset before rejoin.
                        if (isPlaylistStuckOrSourceError(error)) {
                            playbackPosition = 0L
                        }

                        if (retryCount < maxRetries) {
                            retryCount++
                            val delay = if (isPlaylistStuckOrSourceError(error)) {
                                retryDelayMs + 1500L
                            } else {
                                retryDelayMs
                            }
                            showStatus("Reconnecting… ($retryCount/$maxRetries)")
                            Log.d(TAG, "Player error, retrying ($retryCount/$maxRetries): ${error.message}")
                            handler.postDelayed({
                                if (isAdded) restartPlayer(fullReinit = true)
                            }, delay)
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
                        // Never persist live edge offsets for later seeks — they go stale quickly.
                        playbackPosition = if (player?.isCurrentMediaItemLive == true) {
                            0L
                        } else {
                            player?.currentPosition ?: 0
                        }
                        Log.d(TAG, "First frame rendered at position: ${player?.currentPosition}")
                        startStallDetection()
                    }
                })
            }

        playerView?.player = player
        Log.d(TAG, "Player initialized with URL: $urlToPlay decoderMode=$decoderMode")
    }

    /**
     * Build media source. HLS uses a dedicated factory with a more tolerant playlist-stuck
     * coefficient for flaky IPTV gateways (default 3.5× is too aggressive).
     */
    private fun createMediaSource(
        urlToPlay: String,
        format: String?,
        mediaItem: MediaItem
    ): androidx.media3.exoplayer.source.MediaSource {
        if (urlToPlay.startsWith("rtmp://")) {
            val rtmpDataSourceFactory = RtmpDataSource.Factory()
            return DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(mediaItem)
        }

        val dataSourceFactory = httpDataSourceFactory!!
        val isHls = format == "M3U8" ||
            urlToPlay.contains(".m3u8", ignoreCase = true) ||
            mediaItem.localConfiguration?.mimeType.equals("application/x-mpegURL", ignoreCase = true) ||
            mediaItem.localConfiguration?.mimeType.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true)

        return if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setPlaylistTrackerFactory { hlsDataSourceFactory, loadErrorHandlingPolicy, playlistParserFactory ->
                    DefaultHlsPlaylistTracker(
                        hlsDataSourceFactory,
                        loadErrorHandlingPolicy,
                        playlistParserFactory,
                        hlsPlaylistStuckCoefficient
                    )
                }
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }
    }

    /** True for channel-style streams where ENDED usually means "window died", not VOD complete. */
    private fun isLikelyLiveStream(url: String, format: String?, mimeType: String?): Boolean {
        if (url.startsWith("rtmp://")) return true
        if (format == "M3U8") return true
        if (mimeType?.contains("mpegURL", ignoreCase = true) == true) return true
        if (mimeType?.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) == true) return true
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/live") ||
            lower.contains("playlist") ||
            // Common IPTV gateway shapes (php?id=…, streaming.m3u8 proxies)
            (lower.contains(".php") && lower.contains("id="))
    }

    private fun shouldRecoverLiveEnded(): Boolean {
        val p = player
        if (p?.isCurrentMediaItemLive == true) return true
        val url = resolvedUrl ?: return false
        val mimeType = arguments?.getString("mime_type")
        val format = Utils.determineVideoFormat(url, mimeType)
        return isLikelyLiveStream(url, format, mimeType)
    }

    /**
     * After ~8s still READY with frames, treat the stream as healthy and reset recover budgets.
     * Avoids infinite reconnect when the player flashes READY then ENDED every cycle.
     */
    private fun scheduleStablePlaybackCredit() {
        handler.removeCallbacks(stablePlaybackCreditRunnable)
        handler.postDelayed(stablePlaybackCreditRunnable, 8000L)
    }

    private val stablePlaybackCreditRunnable = Runnable {
        val p = player ?: return@Runnable
        if (!isAdded || isShowingFatalError) return@Runnable
        if (p.playbackState == Player.STATE_READY && p.playWhenReady) {
            if (retryCount != 0 || liveRecoverCount != 0) {
                Log.d(TAG, "Stable playback — clearing retry budgets (was retry=$retryCount live=$liveRecoverCount)")
            }
            retryCount = 0
            liveRecoverCount = 0
        }
    }

    private fun scheduleLiveRecover(statusMessage: String) {
        handler.removeCallbacks(stablePlaybackCreditRunnable)
        if (liveRecoverCount >= maxLiveRecovers) {
            isShowingFatalError = true
            showFatalError(
                title = "Stream unavailable",
                message = "Live stream stopped updating. Try again later."
            )
            playerView?.useController = false
            Log.e(TAG, "Live recover exhausted after $maxLiveRecovers attempts")
            releasePlayerSafely()
            player = null
            handler.postDelayed({
                if (isAdded && !isDetached) {
                    parentFragmentManager.popBackStack()
                }
            }, 6000L)
            return
        }

        liveRecoverCount++
        playbackPosition = 0L
        showStatus("$statusMessage ($liveRecoverCount/$maxLiveRecovers)")
        Log.w(TAG, "Live recover $liveRecoverCount/$maxLiveRecovers: $statusMessage")
        handler.postDelayed({
            if (isAdded) restartPlayer(fullReinit = true)
        }, liveRecoverDelayMs)
    }

    private fun isPlaylistStuckOrSourceError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
        ) {
            return true
        }
        var cause: Throwable? = error
        while (cause != null) {
            val name = cause.javaClass.simpleName
            if (name.contains("PlaylistStuck", ignoreCase = true) ||
                name.contains("PlaylistReset", ignoreCase = true)
            ) {
                return true
            }
            val msg = cause.message.orEmpty()
            if (msg.contains("PlaylistStuck", ignoreCase = true) ||
                msg.contains("Source error", ignoreCase = true)
            ) {
                return true
            }
            cause = cause.cause
        }
        return error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
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
        val urlToPlay = resolvedUrl
        val mimeType = arguments?.getString("mime_type")
        val format = urlToPlay?.let { Utils.determineVideoFormat(it, mimeType) }
        val isLikelyLive = urlToPlay != null && isLikelyLiveStream(urlToPlay, format, mimeType)

        // Live/IPTV: always rejoin edge after a stall (stale offsets cause long BUFFERING).
        if (isLikelyLive || p?.isCurrentMediaItemLive == true) {
            playbackPosition = 0L
        }

        if (!isShowingStatus) {
            showStatus("Reconnecting stream…")
        }

        resetStallCounters()

        if (fullReinit || p == null || consecutiveStallRestarts >= maxStallRestartsBeforeFullReinit) {
            consecutiveStallRestarts = 0
            isStallRestarting = true
            releasePlayerSafely()
            currentSurface = null
            urlToPlay?.let { initializePlayer(it) }
            return
        }

        if (!isLikelyLive && p.isCurrentMediaItemLive != true) {
            playbackPosition = p.currentPosition
        }
        lastVideoPosition = 0
        isStallRestarting = true
        consecutiveStallRestarts++

        try {
            p.stop()
            p.clearMediaItems()

            if (urlToPlay == null) return
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

            val mediaSource = createMediaSource(urlToPlay, format, mediaItem)

            p.setMediaSource(mediaSource)
            p.prepare()
            // For live, prefer joining the live edge rather than a stale position after a stall.
            if (isLikelyLive || p.isCurrentMediaItemLive) {
                playbackPosition = 0L
                p.seekToDefaultPosition()
            } else {
                p.seekTo(playbackPosition)
            }
            p.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Lightweight restart failed, doing full reinit", e)
            releasePlayerSafely()
            currentSurface = null
            urlToPlay?.let { initializePlayer(it) }
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
        resetStallCounters()
        isStallRestarting = false
        isShowingFatalError = false
        isShowingStatus = false
        consecutiveStallRestarts = 0
        retryCount = 0
        liveRecoverCount = 0
        decoderMode = DECODER_MODE_HARDWARE
        decoderRetryCount = 0
        excludeEmulatorDecoders = false
        httpDataSourceFactory = null
        trackSelector = null
        Log.d(TAG, "onDestroyView: Player released and views nullified")
    }

    private fun resetStallCounters() {
        bufferingStallChecks = 0
        frozenStallChecks = 0
        didShowBufferingHint = false
    }

    private fun startStallDetection() {
        handler.removeCallbacks(stallCheckRunnable)
        lastVideoPosition = player?.currentPosition ?: 0
        resetStallCounters()
        handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
    }

    /** Resume stall polling if it was cancelled; does not reset counters (for BUFFERING). */
    private fun ensureStallDetectionRunning() {
        handler.removeCallbacks(stallCheckRunnable)
        handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
    }

    private fun checkForVideoStall() {
        val p = player
        if (p == null || !p.playWhenReady) {
            resetStallCounters()
            handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
            return
        }

        // Skip while we are already mid-recovery restart
        if (isStallRestarting || isShowingFatalError) {
            handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
            return
        }

        val currentPos = p.currentPosition
        val state = p.playbackState

        if (state == Player.STATE_BUFFERING) {
            // Do not carry frozen ticks into buffering — separate failure modes.
            frozenStallChecks = 0
            bufferingStallChecks++
            Log.d(
                TAG,
                "Stall check: buffering ($bufferingStallChecks/$BUFFERING_STALL_THRESHOLD)"
            )
            if (!didShowBufferingHint &&
                bufferingStallChecks >= BUFFERING_STATUS_HINT_AFTER &&
                !isShowingStatus
            ) {
                didShowBufferingHint = true
                showStatus("Buffering…")
            }
            if (bufferingStallChecks >= BUFFERING_STALL_THRESHOLD) {
                Log.w(TAG, "Video stalled (buffering timeout). Restarting player.")
                showStatus("Stream stalled — reconnecting…")
                // Soft re-prepare first; full re-init after repeated stalls (restartPlayer counts).
                restartPlayer(fullReinit = consecutiveStallRestarts >= 1)
                return
            }
        } else if (state == Player.STATE_READY) {
            bufferingStallChecks = 0
            didShowBufferingHint = false
            val advanced = currentPos - lastVideoPosition
            // Live edge often reports a fixed position while frames still advance.
            // Only treat as frozen when playhead is stuck AND we look starved / not playing.
            val notAdvancing = advanced < 100 && currentPos > 0
            val bufferedAhead = (p.bufferedPosition - currentPos).coerceAtLeast(0L)
            val starving = bufferedAhead < 750L
            val trulyFrozen = notAdvancing && (!p.isPlaying || starving)
            if (trulyFrozen) {
                frozenStallChecks++
                Log.d(
                    TAG,
                    "Stall check: frozen position ($frozenStallChecks/$FROZEN_STALL_THRESHOLD) " +
                        "isPlaying=${p.isPlaying} bufferedAhead=${bufferedAhead}ms"
                )
                if (frozenStallChecks >= FROZEN_STALL_THRESHOLD) {
                    Log.w(TAG, "Video stalled (decoder frozen). Restarting player.")
                    showStatus("Video frozen — recovering…")
                    restartPlayer(fullReinit = true)
                    return
                }
            } else if (advanced >= 100 || p.isPlaying) {
                frozenStallChecks = 0
            }
        } else if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            // Recovery path owns these states; don't thrash stall counters
            resetStallCounters()
        }

        lastVideoPosition = currentPos
        handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
    }
}
