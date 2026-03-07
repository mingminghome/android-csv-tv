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
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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

@UnstableApi
class PlaybackFragment : Fragment() {
    private var player: ExoPlayer? = null
    private var loadingIndicator: ProgressBar? = null
    private var playerView: PlayerView? = null
    private var errorText: TextView? = null
    private var retryStatusText: TextView? = null
    private var playbackPosition: Long = 0
    private var currentMediaItem: MediaItem? = null
    private var resolvedUrl: String? = null

    private var retryCount = 0
    private val maxRetries = 5 // Increased retries for better stability
    private val retryDelayMs = 5000L // Increased delay between retries
    private val handler = Handler(Looper.getMainLooper())

    // Buffer settings for smoother loading on slow networks
    private val minBufferMs = 120000 // 2 minutes
    private val maxBufferMs = 300000 // 5 minutes
    private val bufferForPlaybackMs = 20000 // 20 seconds before starting
    private val bufferForPlaybackAfterRebufferMs = 30000 // 30 seconds after rebuffering

    private val bufferingTimeoutMs = 20000L // 20 seconds
    private val bufferingTimeoutRunnable = Runnable {
        Log.w("PlaybackFragment", "Buffering timed out. Restarting player.")
        retryStatusText?.visibility = View.VISIBLE
        retryStatusText?.text = "Connection slow, reconnecting..."
        restartPlayer()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        resolvedUrl = arguments?.getString("video_url")
        playerView = view.findViewById(R.id.player_view)
        errorText = view.findViewById(R.id.error_text)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        
        // Add a new TextView for retry status if it exists in layout, or create one
        retryStatusText = view.findViewById(R.id.retry_status_text) ?: createRetryStatusText(view)

        (loadingIndicator?.layoutParams as? RelativeLayout.LayoutParams)?.apply {
            addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
        }

        playerView?.useController = false
        playerView?.keepScreenOn = true

        playbackPosition = savedInstanceState?.getLong("playback_position", 0) ?: 0
    }

    private fun createRetryStatusText(root: View): TextView {
        val tv = TextView(requireContext()).apply {
            id = View.generateViewId()
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            visibility = View.GONE
        }
        val params = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.BELOW, R.id.loading_indicator)
            topMargin = 20
        }
        (root as? RelativeLayout)?.addView(tv, params)
        return tv
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val currentPos = player?.currentPosition ?: playbackPosition
        outState.putLong("playback_position", currentPos)
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        val urlToPlay = resolvedUrl ?: return
        
        if (player != null) {
            player?.playWhenReady = true
            return
        }

        loadingIndicator?.visibility = View.VISIBLE
        playerView?.visibility = View.GONE

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            .setBackBuffer(30000, true) // Keep 30s in back buffer
            .build()

        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(createUnsafeSslContext().socketFactory, createUnsafeTrustManager())
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient).setUserAgent("ExoPlayer-CSVTV")

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
                    HlsMediaSource.Factory(httpDataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(mediaItem)
                }

                setMediaSource(mediaSource)
                prepare()
                seekTo(playbackPosition)
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        handler.removeCallbacks(bufferingTimeoutRunnable)
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                loadingIndicator?.visibility = View.VISIBLE
                                handler.postDelayed(bufferingTimeoutRunnable, bufferingTimeoutMs)
                            }
                            Player.STATE_READY -> {
                                loadingIndicator?.visibility = View.GONE
                                retryStatusText?.visibility = View.GONE
                                playerView?.visibility = View.VISIBLE
                                playerView?.useController = true
                                retryCount = 0 // Reset retry count on successful play
                            }
                            Player.STATE_ENDED -> {
                                loadingIndicator?.visibility = View.GONE
                                retryStatusText?.visibility = View.GONE
                            }
                            Player.STATE_IDLE -> {
                                // Do nothing
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PlaybackFragment", "Player error: ${error.message}")
                        loadingIndicator?.visibility = View.GONE
                        
                        if (retryCount < maxRetries) {
                            retryCount++
                            retryStatusText?.visibility = View.VISIBLE
                            retryStatusText?.text = "Stream error, retrying ($retryCount/$maxRetries)..."
                            handler.postDelayed({ restartPlayer() }, retryDelayMs)
                        } else {
                            errorText?.visibility = View.VISIBLE
                            errorText?.text = "Failed after $maxRetries attempts.\nPlease check your connection or stream source."
                            retryStatusText?.visibility = View.GONE
                        }
                    }
                })
            }
        playerView?.player = player
    }

    private fun releasePlayer() {
        player?.let {
            playbackPosition = it.currentPosition
            it.release()
            player = null
        }
    }

    private fun restartPlayer() {
        releasePlayer()
        initializePlayer()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
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
}