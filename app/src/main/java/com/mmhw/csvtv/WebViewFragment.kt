package com.mmhw.csvtv

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.json.JSONObject

class WebViewFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var pointer: ImageView
    private lateinit var container: ViewGroup
    private var isDesktopMode = false
    private var isLoading = false
    private lateinit var btnWebBack: ImageButton
    private lateinit var btnWebForward: ImageButton
    private lateinit var btnWebRefresh: ImageButton
    private lateinit var btnWebDesktopToggle: ImageButton
    private lateinit var btnWebAutoFullscreen: ImageButton
    private lateinit var btnWebClose: ImageButton
    private lateinit var webToolbar: View
    private var isToolbarShowing = false
    private var isWebViewDestroyed = false
    private var initialLoadCompleted = false
    private val pointerHideHandler = Handler(Looper.getMainLooper())
    private val pointerHideDelay = 3000L
    private var pointerX = 0f
    private var pointerY = 0f
    private val pointerSpeed = 15f
    private val scrollThreshold = 30f
    private var contentWidth: Int = 0
    private var contentHeight: Int = 0
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var pointerContainer: FrameLayout
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var isInFullscreen = false
    private var isCursorShowing = false
    private var lastDimensionUpdate = 0L
    private val dimensionUpdateDebounce = 1000L
    private val jsHandler = Handler(Looper.getMainLooper())

    private val doubleClickSpeed = 400L
    private val clickHandler = Handler(Looper.getMainLooper())
    private var clickRunnable: Runnable? = null
    private var clickCount = 0

    private var lastMoveKeyCode = -1
    private var moveStartTime = 0L
    private val keyResetHandler = Handler(Looper.getMainLooper())
    private val keyResetRunnable = Runnable { lastMoveKeyCode = -1 }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_webview, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = arguments?.getString("url") ?: return
        webView = view.findViewById(R.id.web_view)
        container = view.findViewById(R.id.webview_container)
        val activityContent = requireActivity().findViewById<ViewGroup>(android.R.id.content)

        webToolbar = view.findViewById(R.id.web_toolbar)
        btnWebBack = view.findViewById(R.id.btn_web_back)
        btnWebForward = view.findViewById(R.id.btn_web_forward)
        btnWebRefresh = view.findViewById(R.id.btn_web_refresh)
        btnWebDesktopToggle = view.findViewById(R.id.btn_web_desktop_toggle)
        btnWebAutoFullscreen = view.findViewById(R.id.btn_web_auto_fullscreen)
        btnWebClose = view.findViewById(R.id.btn_web_close)

        btnWebClose.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        btnWebBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
        btnWebForward.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }
        btnWebRefresh.setOnClickListener {
            if (isLoading) {
                webView.stopLoading()
            } else {
                webView.reload()
            }
        }
        btnWebAutoFullscreen.setOnClickListener {
            detectAndFullscreenMedia()
        }
        btnWebDesktopToggle.setOnClickListener {
            toggleDesktopMode()
        }

        originalOrientation = requireActivity().requestedOrientation

        fullscreenContainer = FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            @Suppress("DEPRECATION")
            setBackgroundColor(resources.getColor(android.R.color.black))
        }
        activityContent?.addView(fullscreenContainer)

        pointerContainer = FrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        activityContent?.addView(pointerContainer)

        setupPointer(pointerContainer)
        setupWebView(url)

        container.isFocusable = true
        container.isFocusableInTouchMode = true
        container.requestFocus()

        val buttonKeyListener = View.OnKeyListener { _, keyCode, event ->
            handleKeyEvent(keyCode, event)
        }
        btnWebBack.setOnKeyListener(buttonKeyListener)
        btnWebForward.setOnKeyListener(buttonKeyListener)
        btnWebRefresh.setOnKeyListener(buttonKeyListener)
        btnWebAutoFullscreen.setOnKeyListener(buttonKeyListener)
        btnWebDesktopToggle.setOnKeyListener(buttonKeyListener)
        btnWebClose.setOnKeyListener(buttonKeyListener)

        container.setOnKeyListener { _, keyCode, event ->
            handleKeyEvent(keyCode, event)
        }
    }

    private fun setupPointer(parent: ViewGroup) {
        pointer = ImageView(context).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.cursor)
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(48, 48)
        }
        parent.addView(pointer)

        parent.post {
            pointerX = (parent.width / 2).toFloat()
            pointerY = (parent.height / 2).toFloat()
            updatePointerPosition()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String) {
        webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            allowFileAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowContentAccess = true
            setSupportZoom(false)
        }

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.keepScreenOn = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
                activity?.runOnUiThread {
                    if (isAdded) {
                        btnWebRefresh.setImageResource(R.drawable.ic_close)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
                initialLoadCompleted = true
                activity?.runOnUiThread {
                    if (isAdded) {
                        btnWebRefresh.setImageResource(R.drawable.ic_refresh_icon)
                    }
                }
                updateContentDimensions()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val isRedirect = request.isRedirect
                val hasGesture = request.hasGesture()

                // Allow initial page load redirections without alerts
                if (!initialLoadCompleted) {
                    return false
                }

                // Allow standard explicit clicks (hasGesture = true and not an HTTP redirect)
                if (hasGesture && !isRedirect) {
                    return false
                }

                // Intercept and ask before background script redirects/popups
                showRedirectDialog(url)
                return true
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                showToast("Failed to load page: $description")
                parentFragmentManager.popBackStack()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }

                isInFullscreen = true
                setCursorVisibility(false)
                customView = view
                customViewCallback = callback

                @Suppress("DEPRECATION")
                requireActivity().window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )

                fullscreenContainer.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))

                fullscreenContainer.visibility = View.VISIBLE
                container.visibility = View.INVISIBLE

                fullscreenContainer.isFocusable = true
                fullscreenContainer.isFocusableInTouchMode = true
                fullscreenContainer.requestFocus()
                fullscreenContainer.setOnKeyListener { _, keyCode, event ->
                    handleKeyEvent(keyCode, event)
                }
            }

            override fun onHideCustomView() {
                if (customView == null) return

                isInFullscreen = false

                @Suppress("DEPRECATION")
                requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

                fullscreenContainer.visibility = View.GONE
                container.visibility = View.VISIBLE

                fullscreenContainer.setOnKeyListener(null)
                container.requestFocus()

                fullscreenContainer.removeView(customView)
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null

                setCursorVisibility(true)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val context = context ?: return false
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                
                android.app.AlertDialog.Builder(context)
                    .setTitle("Popup Blocked")
                    .setMessage("A new window is attempting to open. Do you want to allow it?")
                    .setPositiveButton("Allow") { _, _ ->
                        val newWebView = WebView(context)
                        transport?.webView = newWebView
                        resultMsg?.sendToTarget()
                        newWebView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val popupUrl = request?.url?.toString()
                                if (popupUrl != null) {
                                    webView.loadUrl(popupUrl)
                                }
                                return true
                            }
                        }
                    }
                    .setNegativeButton("Block") { _, _ ->
                        resultMsg?.sendToTarget() // just consume it
                    }
                    .show()
                return true
            }
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.loadUrl(url)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
    }

    class AndroidBridge(private val fragment: WebViewFragment) {
        @JavascriptInterface
        @Suppress("unused")
        fun updateDimensions() {
            fragment.activity?.runOnUiThread {
                if (fragment.isAdded) {
                    val now = System.currentTimeMillis()
                    if (now - fragment.lastDimensionUpdate > fragment.dimensionUpdateDebounce) {
                        fragment.lastDimensionUpdate = now
                        fragment.updateContentDimensions()
                    }
                }
            }
        }
    }

    private fun setToolbarVisibility(show: Boolean) {
        if (show == isToolbarShowing) return
        isToolbarShowing = show

        webToolbar.animate().cancel()
        val toolbarHeight = (56f * resources.displayMetrics.density)
        if (show) {
            webToolbar.visibility = View.VISIBLE
            webToolbar.animate()
                .translationY(0f)
                .setDuration(250)
                .withEndAction(null)
                .start()
        } else {
            webToolbar.animate()
                .translationY(-toolbarHeight)
                .setDuration(250)
                .withEndAction {
                    webToolbar.visibility = View.GONE
                }
                .start()
        }
    }

    private fun showRedirectDialog(url: String) {
        activity?.runOnUiThread {
            if (isWebViewDestroyed || !isAdded || context == null) return@runOnUiThread

            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Redirect Blocked")
                .setMessage("The page is trying to navigate automatically to:\n\n$url\n\nDo you want to allow this?")
                .setPositiveButton("Allow") { _, _ ->
                    webView.loadUrl(url)
                }
                .setNegativeButton("Block", null)
                .show()
        }
    }

    private fun detectAndFullscreenMedia() {
        if (isWebViewDestroyed || !isAdded || view == null) return
        val js = """
            (function() {
                var video = document.querySelector('video');
                if (video) {
                    if (video.paused) {
                        video.play();
                    }
                    if (video.requestFullscreen) {
                        video.requestFullscreen();
                    } else if (video.webkitRequestFullscreen) {
                        video.webkitRequestFullscreen();
                    }
                    return "video";
                }
                var iframes = document.querySelectorAll('iframe');
                for (var i = 0; i < iframes.length; i++) {
                    var iframe = iframes[i];
                    if (iframe.requestFullscreen) {
                        iframe.requestFullscreen();
                        return "iframe";
                    } else if (iframe.webkitRequestFullscreen) {
                        iframe.webkitRequestFullscreen();
                        return "iframe";
                    }
                }
                return "not_found";
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { value ->
            if (value == "\"not_found\"") {
                showToast("No active media player found on page.")
            } else {
                showToast("Requesting media fullscreen...")
            }
        }
    }

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        if (isDesktopMode) {
            webView.settings.userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            btnWebDesktopToggle.setImageResource(R.drawable.ic_phone)
            showToast("Requesting Desktop Site")
        } else {
            webView.settings.userAgentString = null
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            btnWebDesktopToggle.setImageResource(R.drawable.ic_desktop)
            showToast("Requesting Mobile Site")
        }
        webView.reload()
    }

    private fun updateContentDimensions() {
        if (isWebViewDestroyed || !isAdded || view == null) return
        webView.evaluateJavascript("(function() { return { width: document.body.scrollWidth, height: document.body.scrollHeight }; })();") { value ->
            if (isWebViewDestroyed || !isAdded || view == null) return@evaluateJavascript
            try {
                val json = JSONObject(value)
                contentWidth = json.getInt("width")
                contentHeight = json.getInt("height")
            } catch (e: Exception) {
                if (!isWebViewDestroyed && isAdded && view != null) {
                    contentWidth = webView.width
                    contentHeight = (webView.contentHeight * webView.scaleY).toInt()
                }
            }
        }
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == lastMoveKeyCode) lastMoveKeyCode = -1
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false

        if (isInFullscreen) {
            if (isCursorShowing) {
                // --- CURSOR MODE ---
                val directionals = listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
                if (keyCode in directionals) {
                    resetPointerHideTimer()
                    val speed = pointerSpeed
                    val (deltaX, deltaY) = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> 0f to -speed
                        KeyEvent.KEYCODE_DPAD_DOWN -> 0f to speed
                        KeyEvent.KEYCODE_DPAD_LEFT -> -speed to 0f
                        KeyEvent.KEYCODE_DPAD_RIGHT -> speed to 0f
                        else -> 0f to 0f
                    }
                    movePointer(deltaX, deltaY)
                    return true
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        performClick()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        setCursorVisibility(false)
                        return true
                    }
                }
            } else {
                // --- GESTURE MODE (CURSOR IS HIDDEN) ---
                when (keyCode) {
                    // *** CHANGE: All 4 directionals now enter Cursor Mode ***
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        setCursorVisibility(true)
                        pointerX = (fullscreenContainer.width / 2).toFloat()
                        pointerY = (fullscreenContainer.height / 2).toFloat()
                        updatePointerPosition()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        handleCenterClick()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        webView.webChromeClient?.onHideCustomView()
                        return true
                    }
                }
            }
            return true
        }

        // --- Non-Fullscreen Logic ---
        if (!container.hasFocus()) container.requestFocus()

        keyResetHandler.removeCallbacks(keyResetRunnable)
        keyResetHandler.postDelayed(keyResetRunnable, 200)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                cancelPendingClick()
                val density = resources.displayMetrics.density
                val toolbarHeight = 56f * density
                val isPointerInToolbar = isToolbarShowing && pointerY < toolbarHeight

                if (isPointerInToolbar) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        navigateToolbarButtons(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                        return true
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        pointerY = 66f * density
                        updatePointerPosition()
                        updateToolbarButtonsFocus()
                        return true
                    }
                }

                val speed = calculatePointerSpeed(keyCode)
                val (deltaX, deltaY) = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> 0f to -speed
                    KeyEvent.KEYCODE_DPAD_DOWN -> 0f to speed
                    KeyEvent.KEYCODE_DPAD_LEFT -> -speed to 0f
                    KeyEvent.KEYCODE_DPAD_RIGHT -> speed to 0f
                    else -> 0f to 0f
                }
                setCursorVisibility(true)
                movePointer(deltaX, deltaY)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleCenterClick()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    if (isToolbarShowing && btnWebClose.isFocused) {
                        parentFragmentManager.popBackStack()
                    } else {
                        setToolbarVisibility(true)
                        btnWebClose.requestFocus()
                        showToast("Press Close button or Back again to exit.")
                    }
                }
                return true
            }
        }
        return false
    }

    private fun handleCenterClick() {
        clickCount++
        if (clickCount == 1) {
            clickRunnable = Runnable {
                if (isInFullscreen) {
                    injectJavaScriptForVideo("if (video.paused) { video.play(); } else { video.pause(); }")
                    setCursorVisibility(true)
                    pointerX = (fullscreenContainer.width / 2).toFloat()
                    pointerY = (fullscreenContainer.height / 2).toFloat()
                    updatePointerPosition()
                } else {
                    performClick()
                }
                clickCount = 0
                clickRunnable = null
            }
            clickHandler.postDelayed(clickRunnable!!, doubleClickSpeed)
        } else if (clickCount >= 2) {
            cancelPendingClick()
            if (isInFullscreen) {
                webView.webChromeClient?.onHideCustomView()
            } else {
                toggleFullscreen()
            }
        }
    }

    private fun cancelPendingClick() {
        if (clickRunnable != null) {
            clickHandler.removeCallbacks(clickRunnable!!)
            clickRunnable = null
        }
        clickCount = 0
    }

    private fun calculatePointerSpeed(keyCode: Int): Float {
        val baseSpeed = this.pointerSpeed
        if (keyCode != lastMoveKeyCode) {
            lastMoveKeyCode = keyCode
            moveStartTime = System.currentTimeMillis()
            return baseSpeed
        }
        val duration = System.currentTimeMillis() - moveStartTime
        if (duration < 300L) return baseSpeed
        val accelerationDuration = duration - 300L
        val speedMultiplier = 1.0f + (accelerationDuration / 1500f) * 3.0f
        return (baseSpeed * speedMultiplier).coerceAtMost(baseSpeed * 4.0f)
    }

    private fun movePointer(deltaX: Float, deltaY: Float) {
        pointerX += deltaX
        pointerY += deltaY

        val boundsView = if (isInFullscreen) fullscreenContainer else container
        val viewWidth = boundsView.width.toFloat()
        val viewHeight = boundsView.height.toFloat()

        pointerX = pointerX.coerceIn(0f, viewWidth - pointer.width.toFloat())
        pointerY = pointerY.coerceIn(0f, viewHeight - pointer.height.toFloat())

        if (!isInFullscreen) {
            val density = resources.displayMetrics.density
            val toolbarThresholdShow = 15f * density
            val toolbarThresholdHide = 80f * density

            if (pointerY < toolbarThresholdShow) {
                setToolbarVisibility(true)
            } else if (pointerY >= toolbarThresholdHide) {
                setToolbarVisibility(false)
            }

            updateToolbarButtonsFocus()

            val scrollThresholdPx = 40f * density

            if (deltaX < 0 && pointerX < scrollThresholdPx && webView.scrollX > 0) {
                webView.scrollBy(-pointerSpeed.toInt(), 0)
            } else if (deltaX > 0 && pointerX > container.width - pointer.width - scrollThresholdPx) {
                val maxScrollX = (contentWidth * webView.scaleX - container.width).toInt().coerceAtLeast(0)
                if (webView.scrollX < maxScrollX) webView.scrollBy(pointerSpeed.toInt(), 0)
            }

            val toolbarHeight = 56f * density
            val isPointerInToolbar = isToolbarShowing && pointerY < toolbarHeight

            var scrollAmount = 0
            if (!isPointerInToolbar) {
                if (pointerY < scrollThresholdPx) {
                    scrollAmount = -pointerSpeed.toInt()
                } else if (pointerY > container.height - pointer.height - scrollThresholdPx) {
                    scrollAmount = pointerSpeed.toInt()
                }
            }

            if (scrollAmount != 0) {
                val cssX = (pointerX / density).toInt()
                val cssY = (pointerY / density).toInt()
                val js = """
                    (function() {
                        var ptX = $cssX;
                        var ptY = $cssY;
                        var amount = $scrollAmount;
                        var el = document.elementFromPoint(ptX, ptY);
                        var scrolled = false;
                        while (el && el !== document.documentElement && el !== document.body) {
                            var style = window.getComputedStyle(el);
                            var isScrollable = (style.overflowY === 'auto' || style.overflowY === 'scroll' || el.scrollHeight > el.clientHeight);
                            if (isScrollable && el.scrollHeight > el.clientHeight) {
                                var oldScroll = el.scrollTop;
                                el.scrollTop += amount;
                                if (Math.abs(el.scrollTop - oldScroll) > 0.5) {
                                    scrolled = true;
                                    break;
                                }
                            }
                            el = el.parentElement;
                        }
                        if (!scrolled) {
                            var elements = document.querySelectorAll('*');
                            for (var i = 0; i < elements.length; i++) {
                                var item = elements[i];
                                var style = window.getComputedStyle(item);
                                if ((style.overflowY === 'auto' || style.overflowY === 'scroll') && item.scrollHeight > item.clientHeight) {
                                    var oldScroll = item.scrollTop;
                                    item.scrollTop += amount;
                                    if (Math.abs(item.scrollTop - oldScroll) > 0.5) {
                                        scrolled = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (!scrolled) {
                            window.scrollBy(0, amount);
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }
        updatePointerPosition()
    }

    private fun updatePointerPosition() {
        pointer.x = pointerX
        pointer.y = pointerY
    }

    private fun toggleFullscreen() {
        val js = """
            (function() {
                var video = document.querySelector('video');
                if (video) {
                    if (document.fullscreenElement || document.webkitFullscreenElement) {
                        document.exitFullscreen ? document.exitFullscreen() : document.webkitExitFullscreen();
                    } else {
                        video.requestFullscreen ? video.requestFullscreen() : video.webkitRequestFullscreen();
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectJavaScriptForVideo(jsAction: String) {
        val fullJs = """
            (function() {
                var video = document.querySelector('video');
                if (video) { $jsAction }
            })();
        """.trimIndent()
        webView.evaluateJavascript(fullJs, null)
    }
    private fun navigateToolbarButtons(isRight: Boolean) {
        if (isWebViewDestroyed || !isAdded || view == null) return
        val buttons = listOf(btnWebBack, btnWebForward, btnWebRefresh, btnWebAutoFullscreen, btnWebDesktopToggle, btnWebClose)
            .filter { it.visibility == View.VISIBLE }

        if (buttons.isEmpty()) return

        var currentFocusedIndex = buttons.indexOfFirst { it.isFocused }
        if (currentFocusedIndex == -1) {
            val btnLocation = IntArray(2)
            var minDistance = Float.MAX_VALUE
            val pointerCenterX = pointerX + pointer.width / 2f
            for (i in buttons.indices) {
                val btn = buttons[i]
                btn.getLocationInWindow(btnLocation)
                val btnLeft = btnLocation[0]
                val btnWidth = btn.width
                val btnCenterX = btnLeft + btnWidth / 2f
                val dist = Math.abs(pointerCenterX - btnCenterX)
                if (dist < minDistance) {
                    minDistance = dist
                    currentFocusedIndex = i
                }
            }
        }

        val targetIndex = if (isRight) {
            (currentFocusedIndex + 1).coerceAtMost(buttons.size - 1)
        } else {
            (currentFocusedIndex - 1).coerceAtLeast(0)
        }

        val targetBtn = buttons[targetIndex]
        val btnLocation = IntArray(2)
        targetBtn.getLocationInWindow(btnLocation)
        val btnLeft = btnLocation[0]
        val btnWidth = targetBtn.width
        val btnTop = btnLocation[1]
        val btnHeight = targetBtn.height

        val boundsView = if (isInFullscreen) fullscreenContainer else container
        
        // Target center coordinates relative to window
        val targetCenterX = btnLeft + btnWidth / 2f
        val targetCenterY = btnTop + btnHeight / 2f

        // Convert window coordinate to local container coordinate
        val containerLocation = IntArray(2)
        boundsView.getLocationInWindow(containerLocation)
        val localX = targetCenterX - containerLocation[0]
        val localY = targetCenterY - containerLocation[1]

        pointerX = localX - pointer.width / 2f
        pointerY = localY - pointer.height / 2f

        pointerX = pointerX.coerceIn(0f, boundsView.width.toFloat() - pointer.width)
        pointerY = pointerY.coerceIn(0f, boundsView.height.toFloat() - pointer.height)

        updatePointerPosition()
        updateToolbarButtonsFocus()
    }

    private fun updateToolbarButtonsFocus() {
        if (isWebViewDestroyed || !isAdded || view == null) return
        val density = resources.displayMetrics.density
        val toolbarHeight = 56f * density
        val isPointerInToolbar = isToolbarShowing && pointerY < toolbarHeight

        if (isPointerInToolbar) {
            pointer.visibility = View.INVISIBLE
            val buttons = listOf(btnWebBack, btnWebForward, btnWebRefresh, btnWebAutoFullscreen, btnWebDesktopToggle, btnWebClose)
            val btnLocation = IntArray(2)

            var nearestBtn: ImageButton? = null
            var minDistance = Float.MAX_VALUE
            val pointerCenterX = pointerX + pointer.width / 2f

            for (btn in buttons) {
                if (btn.visibility == View.VISIBLE) {
                    btn.getLocationInWindow(btnLocation)
                    val btnLeft = btnLocation[0]
                    val btnWidth = btn.width
                    val btnCenterX = btnLeft + btnWidth / 2f

                    val distance = Math.abs(pointerCenterX - btnCenterX)
                    if (distance < minDistance) {
                        minDistance = distance
                        nearestBtn = btn
                    }
                }
            }

            for (btn in buttons) {
                if (btn == nearestBtn) {
                    if (!btn.isFocused) {
                        btn.requestFocus()
                    }
                } else {
                    if (btn.isFocused) {
                        btn.clearFocus()
                    }
                }
            }
        } else {
            pointer.visibility = View.VISIBLE
            isCursorShowing = true
            if (!container.hasFocus()) {
                container.requestFocus()
            }
            btnWebBack.clearFocus()
            btnWebForward.clearFocus()
            btnWebRefresh.clearFocus()
            btnWebAutoFullscreen.clearFocus()
            btnWebDesktopToggle.clearFocus()
            btnWebClose.clearFocus()
        }
    }

    private fun setCursorVisibility(show: Boolean) {
        if (show) {
            isCursorShowing = true
            val density = resources.displayMetrics.density
            val toolbarHeight = 56f * density
            val isPointerInToolbar = isToolbarShowing && pointerY < toolbarHeight
            if (!isPointerInToolbar) {
                pointer.visibility = View.VISIBLE
            }
            pointer.bringToFront()
            pointerContainer.requestLayout()
            resetPointerHideTimer()
        } else {
            isCursorShowing = false
            pointer.visibility = View.INVISIBLE
            pointerHideHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun resetPointerHideTimer() {
        pointerHideHandler.removeCallbacksAndMessages(null)
        pointerHideHandler.postDelayed({ setCursorVisibility(false) }, pointerHideDelay)
    }

    private fun performClick() {
        // In normal mode, a click should always show the pointer
        if (!isInFullscreen) {
            setCursorVisibility(true)
        } else {
            // In fullscreen cursor mode, a click should reset the hide timer
            resetPointerHideTimer()
        }

        val x = pointerX.toInt()
        val y = pointerY.toInt()
        val downTime = System.currentTimeMillis()
        val eventTime = downTime + 10
        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)

        if (isInFullscreen) {
            fullscreenContainer.dispatchTouchEvent(downEvent)
            fullscreenContainer.dispatchTouchEvent(upEvent)
        } else {
            container.dispatchTouchEvent(downEvent)
            container.dispatchTouchEvent(upEvent)
        }

        downEvent.recycle()
        upEvent.recycle()
    }

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            if (isAdded) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.keepScreenOn = false
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        container.requestFocus()
        updateContentDimensions()
    }

    override fun onDestroyView() {
        isWebViewDestroyed = true
        super.onDestroyView()
        pointerHideHandler.removeCallbacksAndMessages(null)
        keyResetHandler.removeCallbacksAndMessages(null)
        jsHandler.removeCallbacksAndMessages(null)
        clickHandler.removeCallbacksAndMessages(null)

        if (isInFullscreen) {
            webView.webChromeClient?.onHideCustomView()
        }

        val activityContent = requireActivity().findViewById<ViewGroup>(android.R.id.content)
        activityContent?.removeView(pointerContainer)
        activityContent?.removeView(fullscreenContainer)

        val viewGroup = webView.parent as? ViewGroup
        viewGroup?.removeView(webView)
        webView.stopLoading()
        webView.onPause()
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.removeAllViews()
        android.webkit.WebStorage.getInstance().deleteAllData()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
        webView.destroy()
    }
}