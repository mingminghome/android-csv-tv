package com.mmhw.csvtv

import android.os.Bundle
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
import androidx.media3.datasource.okhttp.OkHttpDataSource // Added missing import
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

    // Buffer parameters
    private val minBufferMs = 30000 // Minimum buffer size (must be largest)
    private val maxBufferMs = 50000 // Maximum buffer size
    private val bufferForPlaybackMs = 2500 // Buffer before starting playback
    private val bufferForPlaybackAfterRebufferMs = 5000 // Buffer after rebuffering

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

        // Find the loading indicator and center it
        loadingIndicator = view.findViewById<ProgressBar>(R.id.loading_indicator)

        // Fix loading indicator position in RelativeLayout
        (loadingIndicator?.layoutParams as? RelativeLayout.LayoutParams)?.apply {
            addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
        }

        // Show loading indicator initially
        loadingIndicator?.visibility = View.VISIBLE

        // Important: Override the XML attribute by setting useController to false initially
        playerView?.useController = false

        // Keep the screen on during playback
        playerView?.keepScreenOn = true

        // Configure buffering parameters with LoadControl
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,  // minBufferMs must be >= bufferForPlaybackAfterRebufferMs
                maxBufferMs,  // maxBufferMs
                bufferForPlaybackMs,  // bufferForPlaybackMs
                bufferForPlaybackAfterRebufferMs   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        // Create a custom OkHttpClient that ignores SSL certificate validation (for HTTPS streams)
        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(createUnsafeSslContext().socketFactory, createUnsafeTrustManager())
            .hostnameVerifier { _, _ -> true }
            .build()

        // Create a DefaultHttpDataSource.Factory using the custom OkHttpClient
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayer-CSVTV")
            .setConnectTimeoutMs(10000) // 10 seconds
            .setReadTimeoutMs(10000)    // 10 seconds

        // Initialize ExoPlayer
        player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .build().apply {
                val mediaItem = MediaItem.fromUri(url)
                val mediaSource = if (url.startsWith("rtmp://")) {
                    val rtmpDataSourceFactory = RtmpDataSource.Factory()
                    DefaultMediaSourceFactory(rtmpDataSourceFactory).createMediaSource(mediaItem)
                } else if (url.endsWith(".m3u8")) {
                    // Use the custom OkHttpClient for HLS streams
                    val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                    HlsMediaSource.Factory(okHttpDataSourceFactory)
                        .createMediaSource(mediaItem) // Fixed: Use the correct method
                } else {
                    // Use the default HTTP data source for other streams (e.g., .mp4)
                    DefaultMediaSourceFactory(httpDataSourceFactory).createMediaSource(mediaItem)
                }
                setMediaSource(mediaSource)
                prepare()

                // Don't start playback immediately
                playWhenReady = false

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                // Show loading indicator and hide error text
                                loadingIndicator?.visibility = View.VISIBLE
                                errorText.visibility = View.GONE

                                // Completely disable and hide controls during buffering
                                playerView?.useController = false
                                playerView?.hideController() // Force hide controller
                            }
                            Player.STATE_READY -> {
                                // Hide loading indicator
                                loadingIndicator?.visibility = View.GONE

                                // Enable player controls once ready
                                playerView?.useController = true

                                // Once we're buffered and ready, start playback
                                playWhenReady = true
                            }
                            Player.STATE_ENDED -> {
                                // Hide loading indicator when playback ends
                                loadingIndicator?.visibility = View.GONE

                                // Keep controls enabled at the end
                                playerView?.useController = true
                            }
                            Player.STATE_IDLE -> {
                                // In idle state, hide loading and keep controls enabled
                                loadingIndicator?.visibility = View.GONE
                                playerView?.useController = true
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // Hide loading indicator and show error
                        loadingIndicator?.visibility = View.GONE
                        errorText.visibility = View.VISIBLE
                        errorText.text = "Failed to play stream: ${error.message}"

                        // Enable controls on error to allow retry if needed
                        playerView?.useController = true
                    }
                })
            }

        playerView?.player = player
    }

    // Create an SSLContext that trusts all certificates (unsafe)
    private fun createUnsafeSslContext(): SSLContext {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(createUnsafeTrustManager()), SecureRandom())
        return sslContext
    }

    // Create a TrustManager that trusts all certificates (unsafe)
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
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clear the keepScreenOn flag to allow the screen to turn off
        playerView?.keepScreenOn = false
        player?.release()
        player = null
        playerView = null
        loadingIndicator = null
    }
}