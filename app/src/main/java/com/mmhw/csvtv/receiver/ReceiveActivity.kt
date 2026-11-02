package com.mmhw.csvtv.receiver

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.mmhw.csvtv.AppChrome
import com.mmhw.csvtv.MainActivity
import com.mmhw.csvtv.WebViewFragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.mmhw.csvtv.AppToolbar
import com.mmhw.csvtv.R
import java.util.concurrent.Executors

/**
 * Idle screen plus photo / video sink for LAN senders.
 * The Rust library only advertises and emits URLs; this activity fetches them.
 */
@UnstableApi
class ReceiveActivity : AppCompatActivity(), NativeReceiver.Listener, WebViewFragment.Closer {
    private var player: ExoPlayer? = null
    private var currentUri: String? = null

    private lateinit var idle: View
    private lateinit var toolbar: View
    private lateinit var mediaBack: Button
    private lateinit var photo: ImageView
    private lateinit var playerView: PlayerView
    private lateinit var webHost: FrameLayout
    private lateinit var hint: TextView
    private lateinit var titleView: TextView
    private lateinit var idView: TextView
    private lateinit var errorView: TextView
    private lateinit var confirmPanel: View
    private lateinit var confirmMessage: TextView
    private var pendingUri: String? = null
    private var pendingMime: String? = null
    private var pendingTitle: String? = null
    private var pendingKind: Int = NativeReceiver.MEDIA_UNKNOWN
    private var liveTs = false
    private var liveRetries = 0
    private var album: Album? = null
    private var albumIndex = 0
    private var albumPaused = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var albumBadge: TextView
    private val albumAdvance = Runnable { showAlbumItem(albumIndex + 1) }
    private lateinit var albumGestures: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppChrome.prepare(this)
        setContentView(R.layout.activity_receive)
        AppChrome.padSystemBars(this)

        idle = findViewById(R.id.receive_idle)
        toolbar = findViewById(R.id.app_toolbar)
        mediaBack = findViewById(R.id.receive_media_back)
        photo = findViewById(R.id.receive_photo)
        playerView = findViewById(R.id.receive_player)
        webHost = findViewById(R.id.receive_web_host)
        hint = findViewById(R.id.receive_hint)
        titleView = findViewById(R.id.toolbar_title)
        idView = findViewById(R.id.receive_id)
        errorView = findViewById(R.id.receive_error)
        val shortId = ReceiverId.shortId(this)
        idView.text = getString(R.string.receiver_id_label, shortId)
        albumBadge = findViewById(R.id.receive_album_index)
        confirmPanel = findViewById(R.id.receive_confirm)
        confirmMessage = findViewById(R.id.receive_confirm_message)
        AppToolbar.bind(
            activity = this,
            title = getString(R.string.receiver_idle_title),
            subtitle = getString(R.string.receiver_id_label, shortId),
            onBack = { handleReceiveBack() }
        )
        CastOverflow.attach(this) {
            refreshCastState()
            if (ReceiveConnect.autoConnect(this) && pendingUri != null) {
                acceptPending()
            }
        }
        mediaBack.setOnClickListener { handleReceiveBack() }
        findViewById<Button>(R.id.receive_confirm_accept).setOnClickListener { acceptPending() }
        findViewById<Button>(R.id.receive_confirm_decline).setOnClickListener { declinePending() }
        albumGestures = GestureDetector(this, albumGestureListener())
        photo.setOnTouchListener { _, event ->
            album != null && albumGestures.onTouchEvent(event)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleReceiveBack()
            }
        })

        NativeReceiver.addListener(this)
        if (NativeReceiver.available) {
            ReceiverService.start(this)
        }
        refreshCastState()

        applyIntent(intent, force = true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && album != null) {
            when (ReceiveKeys.albumAction(event.keyCode)) {
                ReceiveKeys.NEXT -> {
                    showAlbumItem(albumIndex + 1)
                    return true
                }
                ReceiveKeys.PREV -> {
                    showAlbumItem(albumIndex - 1)
                    return true
                }
                ReceiveKeys.PAUSE -> {
                    toggleAlbumPause()
                    return true
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            (confirmVisible() || showingMedia()) &&
            !webShowing()
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                leaveCurrentMedia()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        refreshCastState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent, force = false)
    }

    override fun onDestroy() {
        NativeReceiver.removeListener(this)
        stopAlbum()
        releasePlayer()
        photo.setImageDrawable(null)
        hideWeb()
        if (isFinishing) {
            ReceiverService.stop(this)
        }
        super.onDestroy()
    }

    override fun onReceiverEvent(event: ReceiverNativeEvent) {
        when (event.kind) {
            NativeReceiver.EVENT_PLAY -> showMedia(
                uri = event.uri,
                mime = event.mime,
                title = event.title,
                mediaKind = event.mediaKind,
                force = false,
                accepted = false
            )
            NativeReceiver.EVENT_PAUSE -> player?.pause()
            NativeReceiver.EVENT_STOP -> showIdle()
            NativeReceiver.EVENT_VOLUME -> {
                val percent = event.uri.toIntOrNull() ?: return
                val muted = event.mediaKind != 0
                player?.volume = if (muted) 0f else (percent.coerceIn(0, 100) / 100f)
            }
            NativeReceiver.EVENT_ERROR -> {
                errorView.visibility = View.VISIBLE
                errorView.text = event.uri.ifBlank { "Receiver error" }
            }
        }
    }

    private fun applyIntent(intent: Intent?, force: Boolean) {
        val uri = intent?.getStringExtra(EXTRA_URI).orEmpty()
        if (uri.isBlank()) return
        showMedia(
            uri = uri,
            mime = intent?.getStringExtra(EXTRA_MIME),
            title = intent?.getStringExtra(EXTRA_TITLE),
            mediaKind = intent?.getIntExtra(EXTRA_MEDIA_KIND, NativeReceiver.MEDIA_UNKNOWN)
                ?: NativeReceiver.MEDIA_UNKNOWN,
            force = force,
            accepted = false
        )
    }

    private fun showMedia(
        uri: String,
        mime: String?,
        title: String?,
        mediaKind: Int,
        force: Boolean,
        accepted: Boolean
    ) {
        if (uri.isBlank()) return
        if (!isHttpUrl(uri)) {
            releasePlayer()
            hideWeb()
            photo.visibility = View.GONE
            playerView.visibility = View.GONE
            idle.visibility = View.GONE
            errorView.visibility = View.VISIBLE
            errorView.text = getString(R.string.receiver_blocked_url)
            return
        }
        if (!force && uri == currentUri &&
            (player != null || photo.visibility == View.VISIBLE || webShowing())
        ) {
            player?.play()
            hideConfirm()
            return
        }
        if (!accepted && ReceiveConnect.confirmEnabled(this) && uri != currentUri) {
            pendingUri = uri
            pendingMime = mime
            pendingTitle = title
            pendingKind = mediaKind
            showConfirm(title, uri)
            return
        }
        hideConfirm()
        currentUri = uri
        errorView.visibility = View.GONE
        titleView.text = title?.takeIf { it.isNotBlank() } ?: getString(R.string.receiver_idle_title)

        if (Album.isAlbum(uri, mime)) {
            hideWeb()
            showReceiveChrome(media = true)
            stopAlbum()
            loadAlbum(uri)
            return
        }
        stopAlbum()

        val kind = SendMedia.mediaKind(uri, mime, mediaKind)

        if (kind == NativeReceiver.MEDIA_WEB) {
            releasePlayer()
            photo.visibility = View.GONE
            playerView.visibility = View.GONE
            idle.visibility = View.GONE
            showReceiveChrome(media = true, overlayBack = false)
            showWeb(uri)
            return
        }

        hideWeb()
        showReceiveChrome(media = true)

        if (kind == NativeReceiver.MEDIA_IMAGE) {
            releasePlayer()
            idle.visibility = View.GONE
            playerView.visibility = View.GONE
            photo.visibility = View.VISIBLE
            Glide.with(this).load(uri).fitCenter().into(photo)
            return
        }

        photo.visibility = View.GONE
        Glide.with(this).clear(photo)
        idle.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        playVideo(uri, mime)
    }

    private fun playVideo(uri: String, mime: String?) {
        ensurePlayer()
        val p = player ?: return
        val path = uri.substringBefore('?').substringBefore('#').lowercase()
        liveTs = mime?.contains("mp2t") == true ||
            mime?.contains("mpegts") == true ||
            path.endsWith(".ts")
        liveRetries = 0
        if (liveTs) {
            playLiveTs(p, uri)
            return
        }
        val item = MediaItem.Builder()
            .setUri(uri)
            .apply {
                val declared = mime?.takeIf { it.isNotBlank() }
                if (declared != null) {
                    setMimeType(declared)
                }
            }
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.play()
    }

    private fun playLiveTs(p: ExoPlayer, uri: String) {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("TV,")
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setTsExtractorTimestampSearchBytes(188 * 2_000)
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(1_200)
                    .build()
            )
            .build()
        val source = ProgressiveMediaSource.Factory(http, extractors).createMediaSource(item)
        p.setMediaSource(source)
        p.prepare()
        p.play()
    }

    private fun loadAlbum(url: String) {
        idle.visibility = View.GONE
        errorView.visibility = View.GONE
        io.execute {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                conn.useCaches = false
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }
                val parsed = conn.inputStream.bufferedReader().use { Album.parse(it.readText()) }
                main.post {
                    if (isDestroyed) return@post
                    if (parsed.items.isEmpty()) {
                        errorView.visibility = View.VISIBLE
                        errorView.text = getString(R.string.receiver_album_empty)
                        return@post
                    }
                    album = parsed
                    albumIndex = 0
                    titleView.text = parsed.title
                    showAlbumItem(0)
                }
            } catch (e: Exception) {
                Log.w("ReceiveActivity", "album", e)
                main.post {
                    if (isDestroyed) return@post
                    errorView.visibility = View.VISIBLE
                    errorView.text = e.message ?: getString(R.string.receiver_album_empty)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun showAlbumItem(index: Int) {
        val current = album ?: return
        if (current.items.isEmpty()) return
        var i = index
        if (i >= current.items.size) {
            if (!current.loop) {
                showIdle()
                return
            }
            i = 0
        }
        if (i < 0) i = current.items.lastIndex
        albumIndex = i
        albumPaused = false
        val item = current.items[i]
        if (!isHttpUrl(item.url)) {
            releasePlayer()
            photo.visibility = View.GONE
            playerView.visibility = View.GONE
            errorView.visibility = View.VISIBLE
            errorView.text = getString(R.string.receiver_blocked_url)
            return
        }
        refreshAlbumBadge()
        titleView.text = item.title.ifBlank { current.title }
        photo.removeCallbacks(albumAdvance)
        hideWeb()
        idle.visibility = View.GONE
        errorView.visibility = View.GONE
        playerView.useController = false
        if (item.isImage) {
            releasePlayer()
            playerView.visibility = View.GONE
            photo.visibility = View.VISIBLE
            if (!isDestroyed) {
                Glide.with(this).load(item.url).fitCenter().into(photo)
            }
            photo.postDelayed(albumAdvance, current.intervalMs)
        } else {
            photo.visibility = View.GONE
            Glide.with(this).clear(photo)
            playerView.visibility = View.VISIBLE
            playVideo(item.url, item.mime)
        }
    }

    private fun toggleAlbumPause() {
        if (album == null) return
        albumPaused = !albumPaused
        refreshAlbumBadge()
        if (albumPaused) {
            photo.removeCallbacks(albumAdvance)
            player?.pause()
        } else {
            val current = album ?: return
            val item = current.items.getOrNull(albumIndex)
            if (item == null) return
            if (item.isImage) {
                photo.postDelayed(albumAdvance, current.intervalMs)
            } else {
                player?.play()
            }
        }
    }

    private fun refreshAlbumBadge() {
        val current = album ?: return
        if (!this::albumBadge.isInitialized) return
        albumBadge.visibility = View.VISIBLE
        val n = albumIndex + 1
        val total = current.items.size
        albumBadge.text = if (albumPaused) {
            getString(R.string.receiver_album_paused, n, total)
        } else {
            getString(R.string.receiver_album_index, n, total)
        }
    }

    private fun stopAlbum() {
        photo.removeCallbacks(albumAdvance)
        album = null
        albumIndex = 0
        albumPaused = false
        if (this::playerView.isInitialized) {
            playerView.useController = true
        }
        if (this::albumBadge.isInitialized) {
            albumBadge.visibility = View.GONE
        }
    }

    private fun showingMedia(): Boolean {
        return album != null ||
            photo.visibility == View.VISIBLE ||
            playerView.visibility == View.VISIBLE ||
            webShowing()
    }

    private fun webShowing(): Boolean {
        return this::webHost.isInitialized && webHost.visibility == View.VISIBLE
    }

    override fun onWebViewClosed() {
        webHost.post {
            if (isDestroyed) return@post
            showIdle()
        }
    }

    private fun handleReceiveBack() {
        if (!leaveCurrentMedia()) {
            leaveReceive()
        }
    }

    private fun showReceiveChrome(media: Boolean, overlayBack: Boolean = media) {
        toolbar.visibility = if (media) View.GONE else View.VISIBLE
        mediaBack.visibility = if (overlayBack) View.VISIBLE else View.GONE
    }

    /** Stop the current photo, video, page, or slideshow. False when already idle. */
    private fun leaveCurrentMedia(): Boolean {
        if (confirmVisible()) {
            declinePending()
            return true
        }
        if (showingMedia()) {
            showIdle()
            return true
        }
        return false
    }

    private fun leaveReceive() {
        if (isTaskRoot) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        finish()
    }

    private fun albumGestureListener() = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = album != null

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (album == null) return false
            toggleAlbumPause()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (album == null || e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (kotlin.math.abs(dx) < 80 || kotlin.math.abs(dx) < kotlin.math.abs(dy)) return false
            if (dx < 0) showAlbumItem(albumIndex + 1) else showAlbumItem(albumIndex - 1)
            return true
        }
    }

    private fun showIdle() {
        currentUri = null
        liveTs = false
        liveRetries = 0
        hideConfirm()
        stopAlbum()
        releasePlayer()
        hideWeb()
        photo.setImageDrawable(null)
        photo.visibility = View.GONE
        playerView.visibility = View.GONE
        idle.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        titleView.text = getString(R.string.receiver_idle_title)
        hint.text = idleHint()
        showReceiveChrome(media = false)
    }

    private fun refreshCastState() {
        if (idle.visibility == View.VISIBLE) {
            hint.text = idleHint()
        }
    }

    private fun confirmVisible(): Boolean {
        return this::confirmPanel.isInitialized && confirmPanel.visibility == View.VISIBLE
    }

    private fun showConfirm(title: String?, uri: String) {
        val label = title?.takeIf { it.isNotBlank() }
            ?: SendMedia.titleOf(uri, getString(R.string.receiver_idle_title))
        confirmMessage.text = getString(R.string.receive_confirm_message, label)
        confirmPanel.visibility = View.VISIBLE
        findViewById<Button>(R.id.receive_confirm_accept).requestFocus()
    }

    private fun hideConfirm() {
        if (this::confirmPanel.isInitialized) {
            confirmPanel.visibility = View.GONE
        }
        pendingUri = null
        pendingMime = null
        pendingTitle = null
        pendingKind = NativeReceiver.MEDIA_UNKNOWN
    }

    private fun acceptPending() {
        val uri = pendingUri ?: return
        val mime = pendingMime
        val title = pendingTitle
        val kind = pendingKind
        hideConfirm()
        showMedia(uri, mime, title, kind, force = true, accepted = true)
    }

    private fun declinePending() {
        hideConfirm()
        if (!showingMedia()) {
            showIdle()
        }
    }

    private fun idleHint(): String {
        return when {
            !NativeReceiver.available -> getString(R.string.receiver_missing_lib)
            ReceiveConnect.confirmEnabled(this) -> getString(R.string.receiver_waiting_confirm)
            CastCrypto.enabled(this) -> getString(R.string.receiver_waiting_encrypted)
            else -> getString(R.string.receiver_waiting)
        }
    }

    private fun ensurePlayer() {
        if (player != null) return
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(800, 4_000, 300, 800)
            .build()
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(renderers)
            .setLoadControl(loadControl)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && album != null) {
                    showAlbumItem(albumIndex + 1)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = error.cause?.message ?: error.message ?: "Playback failed"
                if (album != null) {
                    photo.postDelayed(albumAdvance, 1_200)
                    return
                }
                if (liveTs && liveRetries < 6 && !isDestroyed) {
                    liveRetries++
                    errorView.visibility = View.VISIBLE
                    errorView.text = getString(R.string.receiver_retrying)
                    playerView.postDelayed({
                        if (isDestroyed) return@postDelayed
                        try {
                            player?.prepare()
                            player?.play()
                        } catch (_: Exception) {
                        }
                    }, 250L * liveRetries)
                    return
                }
                errorView.visibility = View.VISIBLE
                errorView.text = detail
            }
        })
        playerView.player = p
        player = p
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun showWeb(uri: String) {
        webHost.visibility = View.VISIBLE
        val page = WebViewFragment.page(uri)
        supportFragmentManager.beginTransaction()
            .replace(R.id.receive_web_host, page, WEB_TAG)
            .commitNowAllowingStateLoss()
    }

    private fun hideWeb() {
        if (!this::webHost.isInitialized) return
        val shown = supportFragmentManager.findFragmentByTag(WEB_TAG)
        if (shown != null && !supportFragmentManager.isStateSaved) {
            supportFragmentManager.beginTransaction()
                .remove(shown)
                .commitNowAllowingStateLoss()
        }
        webHost.visibility = View.GONE
    }

    private fun isHttpUrl(uri: String): Boolean {
        val scheme = uri.trim().substringBefore(':').lowercase()
        return scheme == "http" || scheme == "https"
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MEDIA_KIND = "media_kind"
        private const val WEB_TAG = "receive_web"
    }
}
