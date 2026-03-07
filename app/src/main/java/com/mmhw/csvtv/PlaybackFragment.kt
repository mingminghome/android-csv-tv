package com.mmhw.csvtv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private var errorLayout: LinearLayout? = null
    private var errorMessage: TextView? = null
    private var retryButton: Button? = null
    private var retryStatusText: TextView? = null
    private var playbackPosition: Long = 0
    private var currentMediaItem: MediaItem? = null
    private var resolvedUrl: String? = null

    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelayMs = 5000L
    private val handler = Handler(Looper.getMainLooper())

    private val bufferingTimeoutMs = 20000L
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
        errorLayout = view.findViewById(R.id.error_layout)
        errorMessage = view.findViewById(R.id.error_message)
        retryButton = view.findViewById(R.id.retry_button)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        retryStatusText = view.findViewById(R.id.retry_status_text)

        playerView?.useController = false
        playerView?.keepScreenOn = true

        retryButton?.setOnClickListener {
            hideError()
            retryCount = 0
            restartPlayer()
        }

        playbackPosition = savedInstanceState?.getLong("playback_position", 0) ?: 0
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

        // Optimized buffer settings for TV streams
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 60000, 2500, 5000)
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

                val mediaSource = when {
                    urlToPlay.startsWith("rtmp://") -> {
                        val rtmpDataSourceFactory = RtmpDataSource.Factory()
                        DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(mediaItem)
                    }
                    urlToPlay.lowercase().endsWith(".m3u8") || urlToPlay.lowercase().contains(".m3u8?") -> {
                         HlsMediaSource.Factory(httpDataSourceFactory)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(mediaItem)
                    }
                    else -> {
                        // Default fallback for FLV and other progressive streams
                        DefaultMediaSourceFactory(httpDataSourceFactory).createMediaSource(mediaItem)
                    }
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
                                hideError()
                                retryCount = 0
                            }
                            Player.STATE_ENDED -> {
                                loadingIndicator?.visibility = View.GONE
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PlaybackFragment", "Player error: ${error.message}")
                        loadingIndicator?.visibility = View.GONE
                        
                        if (retryCount < maxRetries) {
                            retryCount++
                            retryStatusText?.visibility = View.VISIBLE
                            retryStatusText?.text = "Connection error, retrying ($retryCount/$maxRetries)..."
                            handler.postDelayed({ restartPlayer() }, retryDelayMs)
                        } else {
                            showError("Failed to play video stream after $maxRetries attempts. Please check the source URL.")
                        }
                    }
                })
            }
        playerView?.player = player
    }

    private fun showError(message: String) {
        errorMessage?.text = message
        errorLayout?.visibility = View.VISIBLE
        retryStatusText?.visibility = View.GONE
        loadingIndicator?.visibility = View.GONE
        playerView?.visibility = View.GONE
    }

    private fun hideError() {
        errorLayout?.visibility = View.GONE
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