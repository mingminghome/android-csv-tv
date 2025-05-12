package com.mmhw.csvtv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import android.util.Log
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class PlaybackFragment : Fragment() {
    private var player: ExoPlayer? = null
    private var loadingIndicator: ProgressBar? = null
    private var playerView: PlayerView? = null
    private var errorText: TextView? = null
    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelayMs = 3000L
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = arguments?.getString("video_url") ?: return
        playerView = view.findViewById<PlayerView>(R.id.player_view)
        errorText = view.findViewById<TextView>(R.id.error_text)
        loadingIndicator = view.findViewById<ProgressBar>(R.id.loading_indicator)

        (loadingIndicator?.layoutParams as? RelativeLayout.LayoutParams)?.apply {
            addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
        }

        loadingIndicator?.visibility = View.VISIBLE
        playerView?.visibility = View.GONE
        errorText?.visibility = View.GONE

        playerView?.useController = false
        playerView?.keepScreenOn = true

        initializePlayer(url)
    }

    private fun initializePlayer(url: String) {
        retryCount = 0

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50000,
                100000,
                5000,
                10000
            )
            .build()

        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(createUnsafeSslContext().socketFactory, createUnsafeTrustManager())
            .hostnameVerifier { _, _ -> true }
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayer-CSVTV")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .build().apply {
                val mediaItem = MediaItem.fromUri(url)
                val mediaSource = when {
                    url.startsWith("rtmp://") -> {
                        val rtmpDataSourceFactory = RtmpDataSource.Factory()
                        DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(mediaItem)
                    }
                    url.endsWith(".m3u8") -> {
                        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                        HlsMediaSource.Factory(okHttpDataSourceFactory)
                            .setAllowChunklessPreparation(true)
                            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))
                            .createMediaSource(mediaItem)
                    }
                    else -> {
                        DefaultMediaSourceFactory(httpDataSourceFactory).createMediaSource(mediaItem)
                    }
                }
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = false

                val bufferingTimeoutRunnable = Runnable {
                    if (playbackState == Player.STATE_BUFFERING) {
                        loadingIndicator?.visibility = View.GONE
                        playerView?.visibility = View.GONE
                        errorText?.visibility = View.VISIBLE
                        errorText?.text = "Stream failed to load after timeout"
                        showToast("Stream failed to load. Please try again.")
                    }
                }

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                loadingIndicator?.visibility = View.VISIBLE
                                playerView?.visibility = View.GONE
                                errorText?.visibility = View.GONE
                                playerView?.useController = false
                                playerView?.hideController()
                                handler.postDelayed(bufferingTimeoutRunnable, 30000)
                            }
                            Player.STATE_READY -> {
                                handler.removeCallbacks(bufferingTimeoutRunnable)
                                loadingIndicator?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                                    loadingIndicator?.visibility = View.GONE
                                    loadingIndicator?.alpha = 1f
                                }?.start()
                                playerView?.alpha = 0f
                                playerView?.visibility = View.VISIBLE
                                playerView?.animate()?.alpha(1f)?.setDuration(200)?.start()
                                playerView?.useController = true
                                playWhenReady = true
                            }
                            Player.STATE_ENDED -> {
                                handler.removeCallbacks(bufferingTimeoutRunnable)
                                loadingIndicator?.visibility = View.GONE
                                playerView?.visibility = View.VISIBLE
                                playerView?.useController = true
                            }
                            Player.STATE_IDLE -> {
                                handler.removeCallbacks(bufferingTimeoutRunnable)
                                loadingIndicator?.visibility = View.GONE
                                playerView?.visibility = View.GONE
                                playerView?.useController = true
                            }
                        }
                        Log.d("PlaybackFragment", "Playback state changed: $playbackState")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handler.removeCallbacks(bufferingTimeoutRunnable)
                        loadingIndicator?.visibility = View.GONE
                        playerView?.visibility = View.GONE
                        if (retryCount < maxRetries) {
                            retryCount++
                            errorText?.visibility = View.VISIBLE
                            errorText?.text = "Playback error, retrying ($retryCount/$maxRetries)..."
                            handler.postDelayed({
                                player?.setMediaSource(player?.currentMediaItem?.let {
                                    when {
                                        url.startsWith("rtmp://") -> {
                                            val rtmpDataSourceFactory = RtmpDataSource.Factory()
                                            DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(it)
                                        }
                                        url.endsWith(".m3u8") -> {
                                            val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                                            HlsMediaSource.Factory(okHttpDataSourceFactory)
                                                .setAllowChunklessPreparation(true)
                                                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))
                                                .createMediaSource(it)
                                        }
                                        else -> {
                                            DefaultMediaSourceFactory(httpDataSourceFactory).createMediaSource(it)
                                        }
                                    }
                                } ?: return@postDelayed)
                                player?.prepare()
                                player?.playWhenReady = true
                            }, retryDelayMs)
                        } else {
                            errorText?.visibility = View.VISIBLE
                            errorText?.text = "Failed to play stream after $maxRetries attempts: ${error.message}"
                            playerView?.useController = true
                            showToast("Failed to play stream: ${error.message}")
                        }
                        Log.e("PlaybackFragment", "Player error: ${error.message}", error)
                    }

                    override fun onIsLoadingChanged(isLoading: Boolean) {
                        Log.d("PlaybackFragment", "Is loading: $isLoading")
                    }

                    override fun onRenderedFirstFrame() {
                        Log.d("PlaybackFragment", "First frame rendered")
                    }
                })
            }

        playerView?.player = player
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

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
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
    }
}