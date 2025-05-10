package com.mmhw.csvtv

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
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

    private val minBufferMs = 30000
    private val maxBufferMs = 50000
    private val bufferForPlaybackMs = 2500
    private val bufferForPlaybackAfterRebufferMs = 5000

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = arguments?.getString("video_url") ?: return
        playerView = view.findViewById<PlayerView>(R.id.player_view)
        val errorText = view.findViewById<TextView>(R.id.error_text)

        loadingIndicator = view.findViewById<ProgressBar>(R.id.loading_indicator)
        (loadingIndicator?.layoutParams as? RelativeLayout.LayoutParams)?.apply {
            addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
        }

        loadingIndicator?.visibility = View.VISIBLE
        playerView?.useController = false
        playerView?.keepScreenOn = true

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
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayer-CSVTV")
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)

        player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .build().apply {
                val mediaItem = MediaItem.fromUri(url)
                val mediaSource = if (url.startsWith("rtmp://")) {
                    val rtmpDataSourceFactory = RtmpDataSource.Factory()
                    DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(mediaItem)
                } else if (url.endsWith(".m3u8")) {
                    val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                    HlsMediaSource.Factory(okHttpDataSourceFactory)
                        .createMediaSource(mediaItem)
                } else {
                    DefaultMediaSourceFactory(httpDataSourceFactory).createMediaSource(mediaItem)
                }
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = false

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                loadingIndicator?.visibility = View.VISIBLE
                                errorText.visibility = View.GONE
                                playerView?.useController = false
                                playerView?.hideController()
                            }
                            Player.STATE_READY -> {
                                loadingIndicator?.visibility = View.GONE
                                playerView?.useController = true
                                playWhenReady = true
                            }
                            Player.STATE_ENDED -> {
                                loadingIndicator?.visibility = View.GONE
                                playerView?.useController = true
                            }
                            Player.STATE_IDLE -> {
                                loadingIndicator?.visibility = View.GONE
                                playerView?.useController = true
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        loadingIndicator?.visibility = View.GONE
                        errorText.visibility = View.VISIBLE
                        val errorMessage = when {
                            error.message?.contains("Cleartext") == true ->
                                "Failed to play stream: HTTP traffic not permitted. Please use HTTPS or contact the app developer."
                            else -> "Failed to play stream: ${error.message}"
                        }
                        errorText.text = errorMessage
                        playerView?.useController = true
                    }
                })
            }

        playerView?.player = player

        view.isFocusable = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                stopPlayback()
                parentFragmentManager.popBackStack()
                true
            } else {
                false
            }
        }
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

    private fun stopPlayback() {
        player?.stop()
        player?.release()
        player = null
        playerView?.player = null
        playerView?.keepScreenOn = false
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPlayback()
        playerView = null
        loadingIndicator = null
    }
}