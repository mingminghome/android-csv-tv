package com.mmhw.csvtv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class PlaybackFragment : Fragment() {
    private var player: ExoPlayer? = null
    private var loadingIndicator: ProgressBar? = null
    private var playerView: PlayerView? = null
    private var errorText: TextView? = null
    private var audioOnlyOverlay: View? = null
    private var playbackPosition: Long = 0
    private var currentMediaItem: MediaItem? = null
    private var currentSurface: Any? = null
    private var resolvedUrl: String? = null
    private var httpDataSourceFactory: OkHttpDataSource.Factory? = null

    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelayMs = 3000L
    private val handler = Handler(Looper.getMainLooper())

    // Stable generous buffers (restored/improved from v1.07 for live stream reliability on Fire TV)
    private val MIN_BUFFER_MS = 60000
    private val MAX_BUFFER_MS = 120000
    private val BUFFER_FOR_PLAYBACK_MS = 5000
    private val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10000

    private var isFirstFrameRendered = false
    private var isStallRestarting = false

    // Stall detector: recovers when video freezes but audio continues (common on some live streams)
    private var lastVideoPosition: Long = 0
    private var lastAudioPosition: Long = 0
    private var stallChecksWithoutProgress = 0
    private val STALL_CHECK_INTERVAL_MS = 2000L
    private val STALL_THRESHOLD = 6 // ~12 seconds of no video progress while playing (less aggressive for live streams)
    private val stallCheckRunnable = Runnable { checkForVideoStall() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val urlFromArgs = arguments?.getString("video_url") ?: return
        playerView = view.findViewById<PlayerView>(R.id.player_view)
        errorText = view.findViewById<TextView>(R.id.error_text)
        loadingIndicator = view.findViewById<ProgressBar>(R.id.loading_indicator)
        audioOnlyOverlay = view.findViewById<View>(R.id.audio_only_overlay)

        (loadingIndicator?.layoutParams as? RelativeLayout.LayoutParams)?.apply {
            addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
        }

        loadingIndicator?.visibility = View.VISIBLE
        playerView?.visibility = View.GONE
        errorText?.visibility = View.GONE

        playerView?.useController = false
        playerView?.keepScreenOn = true

        playbackPosition = savedInstanceState?.getLong("playback_position", 0) ?: 0
        resolvedUrl = urlFromArgs
        initializePlayer(urlFromArgs)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("playback_position", player?.currentPosition ?: playbackPosition)
        Log.d("PlaybackFragment", "Saving playback position: ${player?.currentPosition ?: playbackPosition}")
    }

    private fun initializePlayer(urlToPlay: String) {
        val newSurface = playerView?.videoSurfaceView
        if (player != null && currentSurface == newSurface && player?.playbackState != Player.STATE_ENDED) {
            player?.seekTo(playbackPosition)
            player?.playWhenReady = true
            Log.d("PlaybackFragment", "Reusing existing player, seeking to $playbackPosition")
            return
        }
        currentSurface = newSurface

        errorText?.visibility = View.GONE
        isFirstFrameRendered = false
        isStallRestarting = false

        if (httpDataSourceFactory == null) {
            val okHttpClient = OkHttpClient.Builder()
                .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
                .sslSocketFactory(createUnsafeSslContext().socketFactory, createUnsafeTrustManager())
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()

            httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("ExoPlayer-CSVTV")
        }

        // Only full release for fresh creation; stall restarts use lighter stop/clear/reprepare
        player?.release()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .build().apply {
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
                            .setTargetOffsetMs(3000)
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
                seekTo(playbackPosition)
                playWhenReady = false

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        handler.removeCallbacks(stallCheckRunnable)
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                if (isStallRestarting) {
                                    // Seamless restart during stall recovery: keep player visible, no loading flash
                                    loadingIndicator?.visibility = View.GONE
                                    playerView?.visibility = View.VISIBLE
                                } else {
                                    loadingIndicator?.visibility = View.VISIBLE
                                    playerView?.visibility = View.GONE
                                }
                                errorText?.visibility = View.GONE
                                audioOnlyOverlay?.visibility = View.GONE
                                playerView?.useController = false
                                playerView?.hideController()
                                Log.d("PlaybackFragment", "Playback state changed: BUFFERING")
                                // Let ExoPlayer buffer naturally with generous settings; no auto-restart on timeout
                            }
                            Player.STATE_READY -> {
                                errorText?.visibility = View.GONE
                                retryCount = 0
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

                                val isAudio = player?.videoFormat == null
                                audioOnlyOverlay?.visibility = if (isAudio) View.VISIBLE else View.GONE

                                if (!wasStallRestarting) {
                                    playerView?.alpha = 0f
                                    playerView?.visibility = if (isAudio) View.GONE else View.VISIBLE
                                    if (!isAudio) {
                                        playerView?.animate()?.alpha(1f)?.setDuration(200)?.start()
                                    }
                                } else {
                                    // Keep visible during seamless stall reload, ensure no alpha reset
                                    playerView?.alpha = 1f
                                    playerView?.visibility = if (isAudio) View.GONE else View.VISIBLE
                                }

                                playerView?.useController = true
                                playWhenReady = true
                                Log.d("PlaybackFragment", "Playback state changed: READY")

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

                                resolvedUrl?.let { url ->
                                    context?.let { ctx ->
                                        Utils.saveAudioMetadata(ctx, url, isAudio, audioChannels)
                                    }
                                }
                            }
                            Player.STATE_ENDED -> {
                                loadingIndicator?.visibility = View.GONE
                                errorText?.visibility = View.GONE
                                audioOnlyOverlay?.visibility = View.GONE
                                playerView?.visibility = View.VISIBLE
                                playerView?.useController = true
                                Log.d("PlaybackFragment", "Playback state changed: ENDED")
                            }
                            Player.STATE_IDLE -> {
                                loadingIndicator?.visibility = View.GONE
                                errorText?.visibility = View.GONE
                                audioOnlyOverlay?.visibility = View.GONE
                                playerView?.visibility = View.GONE
                                playerView?.useController = true
                                Log.d("PlaybackFragment", "Playback state changed: IDLE")
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        loadingIndicator?.visibility = View.GONE
                        playerView?.visibility = View.GONE
                        handler.removeCallbacks(stallCheckRunnable)
                        isStallRestarting = false
                        if (retryCount < maxRetries) {
                            retryCount++
                            errorText?.visibility = View.VISIBLE
                            errorText?.text = "Playback error, retrying ($retryCount/$maxRetries)..."
                            Log.d("PlaybackFragment", "Player error, retrying ($retryCount/$maxRetries): ${error.message}")
                            handler.postDelayed({
                                restartPlayer()
                            }, retryDelayMs)
                        } else {
                            errorText?.visibility = View.VISIBLE
                            errorText?.text = "Failed to play stream after $maxRetries attempts: ${error.message}"
                            playerView?.useController = true
                            Log.e("PlaybackFragment", "Failed to play stream after $maxRetries attempts: ${error.message}")
                            handler.postDelayed({
                                parentFragmentManager.popBackStack()
                            }, 2000L)
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        isFirstFrameRendered = true
                        playbackPosition = player?.currentPosition ?: 0
                        Log.d("PlaybackFragment", "First frame rendered at position: $playbackPosition")
                        startStallDetection()
                    }
                })
            }

        playerView?.player = player
        Log.d("PlaybackFragment", "Player initialized with URL: $urlToPlay")
    }

    private fun restartPlayer() {
        handler.removeCallbacks(stallCheckRunnable)
        val p = player
        if (p != null) {
            playbackPosition = p.currentPosition
            lastVideoPosition = 0
            stallChecksWithoutProgress = 0
            isStallRestarting = true

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
                        .setTargetOffsetMs(3000)
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
            p.seekTo(playbackPosition)
            p.playWhenReady = true
            return
        }

        // Fallback to full re-init
        isStallRestarting = true
        resolvedUrl?.let { initializePlayer(it) }
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
        player?.playWhenReady = true
        Log.d("PlaybackFragment", "onStart: Setting playWhenReady to true")
        // Restart stall detection on resume if we already rendered a frame before
        if (isFirstFrameRendered && player?.playbackState == Player.STATE_READY && player?.videoFormat != null) {
            startStallDetection()
        }
    }

    override fun onStop() {
        super.onStop()
        // Pause only (do not release here to avoid tearing down playback on brief background/home).
        // Release happens in onDestroyView. This restores stable lifecycle from v1.07.
        player?.let {
            playbackPosition = it.currentPosition
            it.playWhenReady = false
            Log.d("PlaybackFragment", "onStop: Pausing playback, saving position: $playbackPosition")
        }
        handler.removeCallbacks(stallCheckRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        playerView?.keepScreenOn = false
        player?.release()
        player = null
        playerView = null
        loadingIndicator = null
        errorText = null
        currentSurface = null
        resolvedUrl = null
        currentMediaItem = null
        lastVideoPosition = 0
        stallChecksWithoutProgress = 0
        isStallRestarting = false
        httpDataSourceFactory = null
        Log.d("PlaybackFragment", "onDestroyView: Player released and views nullified")
    }

    private fun startStallDetection() {
        handler.removeCallbacks(stallCheckRunnable)
        lastVideoPosition = player?.currentPosition ?: 0
        lastAudioPosition = player?.currentPosition ?: 0
        stallChecksWithoutProgress = 0
        handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
    }

    private fun checkForVideoStall() {
        val p = player
        if (p == null || !p.playWhenReady || p.playbackState != Player.STATE_READY) {
            // Not actively playing video; reschedule lightly
            handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
            return
        }
        val currentPos = p.currentPosition
        val hasVideo = p.videoFormat != null
        if (hasVideo && currentPos <= lastVideoPosition + 500) {
            // only count as video stall if audio is still progressing (video frozen but audio continues)
            if (currentPos > lastAudioPosition + 100) {
                stallChecksWithoutProgress++
                Log.d("PlaybackFragment", "Stall check: no video progress ($stallChecksWithoutProgress/$STALL_THRESHOLD), pos=$currentPos")
                if (stallChecksWithoutProgress >= STALL_THRESHOLD) {
                    Log.w("PlaybackFragment", "Video stall detected (frames frozen but sound may continue). Restarting player.")
                    // Do not show toast or UI messages on internal reloads/after load
                    restartPlayer()
                    return
                }
            } else {
                stallChecksWithoutProgress = 0
            }
        } else {
            stallChecksWithoutProgress = 0
        }
        lastVideoPosition = currentPos
        lastAudioPosition = currentPos
        handler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS)
    }
}