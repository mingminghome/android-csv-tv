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
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.json.JSONObject

class WebViewFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var pointer: ImageView
    private lateinit var container: FrameLayout
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

    // Use a desktop User-Agent to force desktop site layouts
    private val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


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
            setSupportZoom(true) // Enable zoom for desktop-style viewing
            userAgentString = DESKTOP_USER_AGENT
        }

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.keepScreenOn = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateContentDimensions()
                injectHideFootersScript()
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
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.loadUrl(url)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
    }

    private fun injectHideFootersScript() {
        val js = """
            (function() {
                var css = 'div[style*="position: fixed"], div[style*="position: sticky"], footer, .cookie-banner, .footer-menu, .mobile-only { display: none !important; }';
                var style = document.createElement('style');
                style.type = 'text/css';
                style.appendChild(document.createTextNode(css));
                document.head.appendChild(style);
                
                // Hide floating elements based on position
                var all = document.getElementsByTagName("*");
                for (var i=0; i < all.length; i++) {
                    var style = window.getComputedStyle(all[i]);
                    if (style.position === 'fixed' || style.position === 'sticky') {
                        var rect = all[i].getBoundingClientRect();
                        // Hide if element is at the bottom of the screen
                        if (rect.bottom >= window.innerHeight - 50 || rect.top >= window.innerHeight * 0.8) {
                             all[i].style.display = 'none';
                        }
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
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

    private fun updateContentDimensions() {
        webView.evaluateJavascript("(function() { return { width: document.body.scrollWidth, height: document.body.scrollHeight }; })();") { value ->
            try {
                val json = JSONObject(value)
                contentWidth = json.getInt("width")
                contentHeight = json.getInt("height")
            } catch (e: Exception) {
                contentWidth = webView.width
                contentHeight = (webView.contentHeight * webView.scaleY).toInt()
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
                if (webView.canGoBack()) webView.goBack() else parentFragmentManager.popBackStack()
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
            if (deltaX < 0 && pointerX < scrollThreshold && webView.scrollX > 0) {
                webView.scrollBy(-pointerSpeed.toInt(), 0)
            } else if (deltaX > 0 && pointerX > container.width - pointer.width - scrollThreshold) {
                val maxScrollX = (contentWidth * webView.scrollX - container.width).toInt().coerceAtLeast(0)
                if (webView.scrollX < maxScrollX) webView.scrollBy(pointerSpeed.toInt(), 0)
            }

            if (deltaY < 0 && pointerY < scrollThreshold && webView.scrollY > 0) {
                webView.scrollBy(0, -pointerSpeed.toInt())
            } else if (deltaY > 0 && pointerY > container.height - pointer.height - scrollThreshold) {
                val maxScrollY = (contentHeight * webView.scaleY - container.height).toInt().coerceAtLeast(0)
                if (webView.scrollY < maxScrollY) webView.scrollBy(0, pointerSpeed.toInt())
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

    private fun setCursorVisibility(show: Boolean) {
        if (show) {
            isCursorShowing = true
            pointer.visibility = View.VISIBLE
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
            webView.dispatchTouchEvent(downEvent)
            webView.dispatchTouchEvent(upEvent)
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
        webView.destroy()
    }
}