package com.mmhw.csvtv.receiver

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mmhw.csvtv.AppChrome
import com.mmhw.csvtv.AppPlayer
import com.mmhw.csvtv.MainActivity
import com.mmhw.csvtv.OrientationPolicy
import com.mmhw.csvtv.WebViewFragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
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
    private lateinit var toolbarRule: View
    private lateinit var mediaBack: Button
    private lateinit var sizeButton: ImageButton
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
    private var liveFullscreen = false
    private var fillScreen = true
    private var playerLive = false
    private var liveBufferLevel = LiveBufferPolicy.startLevel
    private var liveReadyOnce = false
    private var lastBufferAdjustMs = 0L
    private val liveStableCheck = Runnable { onLivePlaybackStable() }
    private var album: Album? = null
    private var albumIndex = 0
    private var albumPaused = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var albumBadge: TextView
    private val albumAdvance = Runnable { showAlbumItem(albumIndex + 1) }
    private lateinit var albumGestures: GestureDetector
    private lateinit var playerGestures: GestureDetector
    private var overlayVisible = true
    private var overlayBackEnabled = false
    private var applyingPlayerController = false
    private val hideOverlayRunnable = Runnable { hideOverlay() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppChrome.prepare(this, OrientationPolicy.Mode.Receive)
        setContentView(R.layout.activity_receive)
        AppChrome.padSystemBars(this) { liveFullscreen }

        idle = findViewById(R.id.receive_idle)
        toolbar = findViewById(R.id.app_toolbar)
        toolbarRule = findViewById(R.id.receive_toolbar_rule)
        mediaBack = findViewById(R.id.receive_media_back)
        sizeButton = findViewById(R.id.receive_screen_size)
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
        CastOverflow.attach(
            activity = this,
            showAutoRotate = true
        ) {
            refreshCastState()
            if (ReceiveConnect.autoConnect(this) && pendingUri != null) {
                acceptPending()
            }
        }
        mediaBack.setOnClickListener { handleReceiveBack() }
        sizeButton.setOnClickListener {
            toggleScreenSize()
            revealOverlay()
        }
        findViewById<Button>(R.id.receive_confirm_accept).setOnClickListener { acceptPending() }
        findViewById<Button>(R.id.receive_confirm_decline).setOnClickListener { declinePending() }
        albumGestures = GestureDetector(this, albumGestureListener())
        playerGestures = GestureDetector(this, playerTapListener())
        photo.setOnTouchListener { _, event ->
            photo.visibility == View.VISIBLE && albumGestures.onTouchEvent(event)
        }
        playerView.controllerShowTimeoutMs = ReceiveChromePolicy.AUTO_HIDE_MS.toInt()
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (applyingPlayerController || liveTs || album != null) return@ControllerVisibilityListener
                if (!chromeAutoHides()) return@ControllerVisibilityListener
                if (visibility == View.VISIBLE) {
                    revealOverlay(showPlayerController = false)
                } else {
                    overlayVisible = false
                    applyOverlayButtons()
                    main.removeCallbacks(hideOverlayRunnable)
                }
            }
        )
        playerView.setOnTouchListener { _, event ->
            if (!liveTs && album == null) return@setOnTouchListener false
            playerGestures.onTouchEvent(event)
            true
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
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_BACK &&
            chromeAutoHides()
        ) {
            val hidden = !overlayVisible
            revealOverlay()
            if (hidden && liveTs && isActivateKey(event.keyCode)) {
                return true
            }
        }
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
        OrientationPolicy.apply(this, OrientationPolicy.Mode.Receive)
        refreshCastState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppChrome.requestInsets(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent, force = false)
    }

    override fun onDestroy() {
        NativeReceiver.removeListener(this)
        main.removeCallbacks(liveStableCheck)
        main.removeCallbacks(hideOverlayRunnable)
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
        clearLiveChrome()

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
        applyLiveChrome()
    }

    private fun playVideo(uri: String, mime: String?) {
        val path = uri.substringBefore('?').substringBefore('#').lowercase()
        liveTs = mime?.contains("mp2t") == true ||
            mime?.contains("mpegts") == true ||
            path.endsWith(".ts")
        liveRetries = 0
        if (liveTs) {
            resetLiveBuffer()
        }
        ensurePlayer(live = liveTs)
        val p = player ?: return
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
            .setTsExtractorTimestampSearchBytes(188 * 200)
        val buf = LiveBufferPolicy.profile(liveBufferLevel)
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(buf.targetOffsetMs.toLong())
                    .setMinOffsetMs(buf.minOffsetMs.toLong())
                    .setMaxOffsetMs(buf.maxOffsetMs.toLong())
                    .build()
            )
            .build()
        val source = ProgressiveMediaSource.Factory(http, extractors).createMediaSource(item)
        p.setMediaSource(source)
        p.prepare()
        p.play()
    }

    private fun resetLiveBuffer() {
        main.removeCallbacks(liveStableCheck)
        liveBufferLevel = LiveBufferPolicy.startLevel
        liveReadyOnce = false
        lastBufferAdjustMs = 0L
    }

    private fun onLivePlaybackState(state: Int) {
        when (state) {
            Player.STATE_READY -> {
                liveReadyOnce = true
                errorView.visibility = View.GONE
                main.removeCallbacks(liveStableCheck)
                main.postDelayed(liveStableCheck, LiveBufferPolicy.stableAfterMs)
            }
            Player.STATE_BUFFERING -> {
                if (!liveReadyOnce) return
                main.removeCallbacks(liveStableCheck)
                applyLiveBuffer(LiveBufferPolicy.afterRebuffer(liveBufferLevel), rebuild = true)
            }
        }
    }

    private fun onLivePlaybackStable() {
        if (isDestroyed || !liveTs) return
        if (player?.playbackState != Player.STATE_READY) return
        applyLiveBuffer(LiveBufferPolicy.afterStable(liveBufferLevel), rebuild = true)
    }

    private fun applyLiveBuffer(newLevel: Int, rebuild: Boolean) {
        val level = newLevel.coerceIn(0, LiveBufferPolicy.maxLevel)
        val now = SystemClock.elapsedRealtime()
        if (level == liveBufferLevel) return
        if (!LiveBufferPolicy.shouldAdjust(now, lastBufferAdjustMs)) return
        liveBufferLevel = level
        lastBufferAdjustMs = now
        Log.i("ReceiveActivity", "live buffer level=$level")
        if (!rebuild || !liveTs) return
        val uri = currentUri ?: return
        liveReadyOnce = false
        main.removeCallbacks(liveStableCheck)
        releasePlayer()
        ensurePlayer(live = true)
        val p = player ?: return
        playLiveTs(p, uri)
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
        val n = albumIndex + 1
        val total = current.items.size
        albumBadge.text = if (albumPaused) {
            getString(R.string.receiver_album_paused, n, total)
        } else {
            getString(R.string.receiver_album_index, n, total)
        }
        applyOverlayButtons()
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
        toolbarRule.visibility = if (media) View.GONE else View.VISIBLE
        overlayBackEnabled = overlayBack && media
        if (media && chromeAutoHides()) {
            revealOverlay()
        } else {
            main.removeCallbacks(hideOverlayRunnable)
            overlayVisible = true
            applyOverlayButtons()
        }
    }

    private fun clearLiveChrome() {
        liveTs = false
        liveRetries = 0
        resetLiveBuffer()
        if (this::sizeButton.isInitialized) {
            sizeButton.visibility = View.GONE
        }
        if (this::playerView.isInitialized) {
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        setLiveFullscreen(false)
    }

    private fun applyLiveChrome() {
        if (!liveTs) {
            clearLiveChrome()
            return
        }
        overlayBackEnabled = false
        playerView.useController = false
        playerView.hideController()
        setLiveFullscreen(true)
        applyScreenSize()
        revealOverlay()
    }

    private fun toggleScreenSize() {
        fillScreen = !fillScreen
        applyScreenSize()
    }

    private fun applyScreenSize() {
        playerView.resizeMode = if (fillScreen) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        if (fillScreen) {
            sizeButton.setImageResource(R.drawable.ic_screen_fit)
            sizeButton.contentDescription = getString(R.string.receiver_screen_fit)
        } else {
            sizeButton.setImageResource(R.drawable.ic_screen_fill)
            sizeButton.contentDescription = getString(R.string.receiver_screen_fill)
        }
    }

    private fun setLiveFullscreen(on: Boolean) {
        if (liveFullscreen == on) {
            if (on) hideSystemBars() else showSystemBars()
            return
        }
        liveFullscreen = on
        AppChrome.requestInsets(this)
        if (on) hideSystemBars() else showSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
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

    private fun chromeAutoHides(): Boolean {
        if (!this::idle.isInitialized) return false
        return ReceiveChromePolicy.autoHides(
            showingMedia = showingMedia(),
            web = webShowing(),
            confirm = confirmVisible(),
            idle = idle.visibility == View.VISIBLE
        )
    }

    private fun revealOverlay(showPlayerController: Boolean = true) {
        overlayVisible = true
        applyOverlayButtons()
        if (showPlayerController &&
            this::playerView.isInitialized &&
            playerView.visibility == View.VISIBLE &&
            !liveTs &&
            album == null
        ) {
            applyingPlayerController = true
            playerView.showController()
            applyingPlayerController = false
        }
        scheduleHideOverlay()
        if (liveTs && sizeButton.visibility == View.VISIBLE) {
            sizeButton.requestFocus()
        }
    }

    private fun hideOverlay() {
        if (!chromeAutoHides()) return
        overlayVisible = false
        applyOverlayButtons()
        main.removeCallbacks(hideOverlayRunnable)
        if (this::playerView.isInitialized &&
            playerView.visibility == View.VISIBLE &&
            !liveTs &&
            album == null
        ) {
            applyingPlayerController = true
            playerView.hideController()
            applyingPlayerController = false
        }
    }

    private fun scheduleHideOverlay() {
        main.removeCallbacks(hideOverlayRunnable)
        if (chromeAutoHides()) {
            main.postDelayed(hideOverlayRunnable, ReceiveChromePolicy.AUTO_HIDE_MS)
        }
    }

    private fun applyOverlayButtons() {
        if (!this::mediaBack.isInitialized) return
        val auto = chromeAutoHides()
        val show = !auto || overlayVisible
        mediaBack.visibility = if (show && overlayBackEnabled && !liveTs) View.VISIBLE else View.GONE
        sizeButton.visibility = if (show && liveTs) View.VISIBLE else View.GONE
        if (this::albumBadge.isInitialized) {
            albumBadge.visibility = if (show && album != null) View.VISIBLE else View.GONE
        }
    }

    private fun onMediaTap(): Boolean {
        if (!chromeAutoHides()) return false
        if (!overlayVisible) {
            revealOverlay()
            return true
        }
        if (album != null) {
            toggleAlbumPause()
            revealOverlay()
            return true
        }
        hideOverlay()
        return true
    }

    private fun isActivateKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_SPACE
    }

    private fun playerTapListener() = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = liveTs || album != null

        override fun onSingleTapUp(e: MotionEvent): Boolean = onMediaTap()
    }

    private fun albumGestureListener() = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = photo.visibility == View.VISIBLE

        override fun onSingleTapUp(e: MotionEvent): Boolean = onMediaTap()

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
            revealOverlay()
            return true
        }
    }

    private fun showIdle() {
        currentUri = null
        hideConfirm()
        stopAlbum()
        releasePlayer()
        hideWeb()
        photo.setImageDrawable(null)
        photo.visibility = View.GONE
        playerView.visibility = View.GONE
        playerView.useController = true
        idle.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        titleView.text = getString(R.string.receiver_idle_title)
        hint.text = idleHint()
        clearLiveChrome()
        overlayBackEnabled = false
        overlayVisible = true
        main.removeCallbacks(hideOverlayRunnable)
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

    private fun ensurePlayer(live: Boolean = false) {
        if (player != null && playerLive == live) return
        if (player != null) releasePlayer()
        val buffers = if (live) {
            val buf = LiveBufferPolicy.profile(liveBufferLevel)
            AppPlayer.Buffers(
                minMs = buf.minBufferMs,
                maxMs = buf.maxBufferMs,
                playbackMs = buf.playbackMs,
                rebufferMs = buf.rebufferMs,
                prioritizeTimeOverSize = true
            )
        } else {
            AppPlayer.Buffers(
                minMs = 800,
                maxMs = 4_000,
                playbackMs = 300,
                rebufferMs = 800
            )
        }
        val p = AppPlayer.create(this, buffers).player
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && album != null) {
                    showAlbumItem(albumIndex + 1)
                    return
                }
                if (liveTs) {
                    onLivePlaybackState(playbackState)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = error.cause?.message ?: error.message ?: "Playback failed"
                if (album != null) {
                    photo.postDelayed(albumAdvance, 1_200)
                    return
                }
                val liveUri = currentUri
                if (liveTs && liveUri != null && liveRetries < 8 && !isDestroyed) {
                    liveRetries++
                    applyLiveBuffer(LiveBufferPolicy.afterError(liveBufferLevel), rebuild = false)
                    Log.w("ReceiveActivity", "live retry $liveRetries: $detail")
                    errorView.visibility = View.VISIBLE
                    errorView.text = getString(R.string.receiver_retrying)
                    playerView.postDelayed({
                        if (isDestroyed || !liveTs || currentUri != liveUri) return@postDelayed
                        releasePlayer()
                        ensurePlayer(live = true)
                        val player = player ?: return@postDelayed
                        playLiveTs(player, liveUri)
                    }, 300L * liveRetries)
                    return
                }
                errorView.visibility = View.VISIBLE
                errorView.text = detail
            }
        })
        playerView.player = p
        player = p
        playerLive = live
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
        playerLive = false
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
