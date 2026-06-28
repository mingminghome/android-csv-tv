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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
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

    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelayMs = 3000L
    private val handler = Handler(Looper.getMainLooper())

    private val minBufferMs = 60000
    private val maxBufferMs = 120000
    private val bufferForPlaybackMs = 5000
    private val bufferForPlaybackAfterRebufferMs = 10000

    private val BUFFERING_TIMEOUT_MS = 10000L // 10 seconds
    private val bufferingTimeoutRunnable = Runnable {
        Log.w("PlaybackFragment", "Buffering timed out. Restarting player.")
        context?.let {
            Toast.makeText(it, "Stream is taking too long to load. Reloading...", Toast.LENGTH_LONG).show()
        }
        restartPlayer()
    }

    private var isFirstFrameRendered = false
    private val BLACK_SCREEN_TIMEOUT_MS = 6000L // 6 seconds
    private val blackScreenTimeoutRunnable = Runnable {
        val hasVideoTrack = player?.videoFormat != null
        if (hasVideoTrack && !isFirstFrameRendered && player?.playWhenReady == true) {
            Log.w("PlaybackFragment", "Black screen detected (STATE_READY but no first frame rendered). Restarting player.")
            context?.let {
                Toast.makeText(it, "Stalled video frame detected. Reloading stream...", Toast.LENGTH_SHORT).show()
            }
            restartPlayer()
        }
    }

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

        isFirstFrameRendered = false
        if (retryCount == 0) {
            retryCount = 0
        }

        player?.release()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs
            )
            .build()

        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(createUnsafeSslContext().socketFactory, createUnsafeTrustManager())
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("ExoPlayer-CSVTV")

        player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .build().apply {
                val mediaItem = MediaItem.Builder()
                    .setUri(urlToPlay)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Video Stream").build())
                    .build()
                this@PlaybackFragment.currentMediaItem = mediaItem

                val mediaSource = if (urlToPlay.startsWith("rtmp://")) {
                    val rtmpDataSourceFactory = RtmpDataSource.Factory()
                    DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(mediaItem)
                } else {
                    DefaultMediaSourceFactory(httpDataSourceFactory).createMediaSource(mediaItem)
                }

                setMediaSource(mediaSource)
                prepare()
                seekTo(playbackPosition)
                playWhenReady = false

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        handler.removeCallbacks(bufferingTimeoutRunnable) // Always remove callback first
                        handler.removeCallbacks(blackScreenTimeoutRunnable)
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                loadingIndicator?.visibility = View.VISIBLE
                                playerView?.visibility = View.GONE
                                errorText?.visibility = View.GONE
                                audioOnlyOverlay?.visibility = View.GONE
                                playerView?.useController = false
                                playerView?.hideController()
                                Log.d("PlaybackFragment", "Playback state changed: BUFFERING")
                                handler.postDelayed(bufferingTimeoutRunnable, BUFFERING_TIMEOUT_MS)
                            }
                            Player.STATE_READY -> {
                                loadingIndicator?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                                    loadingIndicator?.visibility = View.GONE
                                    loadingIndicator?.alpha = 1f
                                }?.start()

                                val isAudio = player?.videoFormat == null
                                audioOnlyOverlay?.visibility = if (isAudio) View.VISIBLE else View.GONE

                                playerView?.alpha = 0f
                                playerView?.visibility = if (isAudio) View.GONE else View.VISIBLE
                                if (!isAudio) {
                                    playerView?.animate()?.alpha(1f)?.setDuration(200)?.start()
                                }

                                playerView?.useController = true
                                playWhenReady = true
                                Log.d("PlaybackFragment", "Playback state changed: READY")
                                if (!isFirstFrameRendered) {
                                    handler.postDelayed(blackScreenTimeoutRunnable, BLACK_SCREEN_TIMEOUT_MS)
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
                                audioOnlyOverlay?.visibility = View.GONE
                                playerView?.visibility = View.VISIBLE
                                playerView?.useController = true
                                Log.d("PlaybackFragment", "Playback state changed: ENDED")
                            }
                            Player.STATE_IDLE -> {
                                loadingIndicator?.visibility = View.GONE
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
                        if (retryCount < maxRetries) {
                            retryCount++
                            errorText?.visibility = View.VISIBLE
                            errorText?.text = "Playback error, retrying ($retryCount/$maxRetries)..."
                            Log.d("PlaybackFragment", "Player error, retrying ($retryCount/$maxRetries): ${error.message}")
                            handler.postDelayed({
                                resolvedUrl?.let { resolvedString ->
                                    currentMediaItem?.let { mediaItemToRetry ->
                                        val mediaSource = if (resolvedString.startsWith("rtmp://")) {
                                            val rtmpDataSourceFactory = RtmpDataSource.Factory()
                                            DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(mediaItemToRetry)
                                        } else {
                                            DefaultMediaSourceFactory(httpDataSourceFactory).createMediaSource(mediaItemToRetry)
                                        }
                                        player?.setMediaSource(mediaSource)
                                        player?.prepare()
                                        player?.seekTo(playbackPosition)
                                        player?.playWhenReady = true
                                    } ?: Log.e("PlaybackFragment", "Retry failed: currentMediaItem is null")
                                } ?: Log.e("PlaybackFragment", "Retry failed: resolvedUrl is null")
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
                        handler.removeCallbacks(blackScreenTimeoutRunnable)
                        playbackPosition = player?.currentPosition ?: 0
                        Log.d("PlaybackFragment", "First frame rendered at position: $playbackPosition")
                    }
                })
            }

        playerView?.player = player
        Log.d("PlaybackFragment", "Player initialized with URL: $urlToPlay")
    }

    private fun restartPlayer() {
        playbackPosition = player?.currentPosition ?: playbackPosition
        player?.release()
        player = null
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
    }

    override fun onStop() {
        super.onStop()
        Log.d("PlaybackFragment", "onStop: Auto-disconnecting media player.")
        player?.release()
        player = null
        
        handler.post {
            if (isAdded && !parentFragmentManager.isStateSaved) {
                parentFragmentManager.popBackStack()
            }
        }
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
        Log.d("PlaybackFragment", "onDestroyView: Player released and views nullified")
    }
}