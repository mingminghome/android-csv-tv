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
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.fragment.app.Fragment

class WebViewFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var pointer: ImageView
    private lateinit var container: FrameLayout
    private val pointerHideHandler = Handler(Looper.getMainLooper())
    private val POINTER_HIDE_DELAY = 3000L // Hide pointer after 3 seconds of inactivity
    private var pointerX = 0f
    private var pointerY = 0f
    private val POINTER_SPEED = 15f // Adjust for faster/slower movement
    private var contentWidth: Int = 0
    private var contentHeight: Int = 0
    private val SCROLL_THRESHOLD = 50f // Pixels from the edge to trigger scrolling
    private val SCROLL_AMOUNT = 100 // Pixels to scroll per step

    // For fullscreen video support
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var isInFullscreen = false

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

        // Store original orientation
        originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Create a container for fullscreen videos
        fullscreenContainer = FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        // Add fullscreen container to the root of the activity
        activity?.findViewById<ViewGroup>(android.R.id.content)?.addView(fullscreenContainer)

        // Add pointer ImageView
        setupPointer(view)

        // Setup WebView
        setupWebView(url)

        // Set focus and key listener on the container
        container.isFocusable = true
        container.isFocusableInTouchMode = true
        container.requestFocus()
        container.setOnKeyListener { _, keyCode, event ->
            println("Key event received: keyCode=$keyCode, action=${event.action}, containerHasFocus=${container.hasFocus()}")
            handleKeyEvent(keyCode, event)
        }

        // Add focus change listener for debugging
        container.setOnFocusChangeListener { _, hasFocus ->
            println("Container focus changed: hasFocus=$hasFocus")
        }
    }

    private fun setupPointer(view: View) {
        // Make sure you have a pointer image in your drawable resources (cursor.png)
        pointer = ImageView(context).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.cursor)
            visibility = View.INVISIBLE

            // Set initial size of the pointer
            layoutParams = FrameLayout.LayoutParams(48, 48)
        }

        // Add pointer to the container
        (view as ViewGroup).addView(pointer)

        // Initialize pointer position to center of screen
        view.post {
            pointerX = (container.width / 2).toFloat()
            pointerY = (container.height / 2).toFloat()
            updatePointerPosition()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String) {
        webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false // Allow autoplay to avoid NotAllowedError
            domStorageEnabled = true // Enable DOM storage for JavaScript
            // Add support for fullscreen video
            allowFileAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowContentAccess = true
            setSupportZoom(true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Get the content dimensions after the page loads
                updateContentDimensions()

                // Inject JavaScript to fix common issues with video players
                injectFullscreenFix()
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                println("WebViewFragment: Error loading page: $description (code: $errorCode)")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // For fullscreen support
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                println("onShowCustomView called")
                if (customView != null) {
                    onHideCustomView()
                    return
                }

                isInFullscreen = true
                customView = view
                customViewCallback = callback

                // Force landscape orientation for better video viewing
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

                // Add the fullscreen view to the fullscreen container (which is at Activity level)
                fullscreenContainer.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))

                // Show fullscreen container and hide the WebView
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.INVISIBLE
                container.visibility = View.INVISIBLE

                // Hide pointer when entering fullscreen
                hidePointer()

                println("Entered fullscreen mode")
            }

            override fun onHideCustomView() {
                println("onHideCustomView called")
                if (customView == null) return

                isInFullscreen = false

                // Pause any playing videos
                webView.evaluateJavascript(
                    """
                    (function() {
                        var videos = document.getElementsByTagName('video');
                        for (var i = 0; i < videos.length; i++) {
                            videos[i].pause();
                        }
                    })();
                    """, null
                )

                // Restore original orientation
                activity?.requestedOrientation = originalOrientation

                // Hide fullscreen container and show the WebView again
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                container.visibility = View.VISIBLE

                // Remove the custom view
                fullscreenContainer.removeView(customView)

                // Call callback to notify that we're done
                customViewCallback?.onCustomViewHidden()

                customView = null
                customViewCallback = null

                // Restore focus to container
                container.requestFocus()
                println("Exited fullscreen mode")
            }
        }

        // Add hardware acceleration for better video performance
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.loadUrl(url)

        // Prevent WebView from taking focus on touch
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
    }

    private fun injectFullscreenFix() {
        // Inject JavaScript to ensure proper fullscreen API usage by the video player
        val js = """
            (function() {
                // Override the default fullscreen API implementation
                var videoElements = document.getElementsByTagName('video');
                for (var i = 0; i < videoElements.length; i++) {
                    videoElements[i].addEventListener('webkitbeginfullscreen', function() {
                        console.log('Video entered fullscreen');
                    });
                    
                    // Add click listeners to any fullscreen buttons if needed
                    var fullscreenButtons = document.querySelectorAll('.fullscreen-button, .ytp-fullscreen-button, [aria-label="Fullscreen"]');
                    for (var j = 0; j < fullscreenButtons.length; j++) {
                        fullscreenButtons[j].addEventListener('click', function() {
                            console.log('Fullscreen button clicked');
                        });
                    }
                }
                
                // Force hardware acceleration for all video elements
                var style = document.createElement('style');
                style.textContent = 'video { transform: translateZ(0); }';
                document.head.appendChild(style);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    // Update the content dimensions using JavaScript
    private fun updateContentDimensions() {
        // Get content width
        webView.evaluateJavascript(
            "Math.max(document.body.scrollWidth, document.documentElement.scrollWidth)",
            ValueCallback { value ->
                contentWidth = value.toIntOrNull() ?: 0
                println("Content width updated: $contentWidth")
            }
        )

        // Get content height
        webView.evaluateJavascript(
            "Math.max(document.body.scrollHeight, document.documentElement.scrollHeight)",
            ValueCallback { value ->
                contentHeight = value.toIntOrNull() ?: 0
                println("Content height updated: $contentHeight")
            }
        )
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        // Handle back button for exiting fullscreen mode
        if (keyCode == KeyEvent.KEYCODE_BACK && isInFullscreen) {
            (webView.webChromeClient as WebChromeClient).onHideCustomView()
            return true
        }

        // Skip regular navigation if in fullscreen mode
        if (isInFullscreen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    // In fullscreen mode, play/pause the video
                    webView.evaluateJavascript(
                        """
                        (function() {
                            var videos = document.getElementsByTagName('video');
                            if (videos.length > 0) {
                                if (videos[0].paused) {
                                    videos[0].play();
                                } else {
                                    videos[0].pause();
                                }
                                return true;
                            }
                            return false;
                        })()
                        """, null
                    )
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    (webView.webChromeClient as WebChromeClient).onHideCustomView()
                    return true
                }
            }
            return false
        }

        // Ensure the container retains focus
        if (!container.hasFocus()) {
            container.requestFocus()
            println("Focus restored to container in handleKeyEvent")
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                showPointer()
                movePointer(0f, -POINTER_SPEED)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showPointer()
                movePointer(0f, POINTER_SPEED)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showPointer()
                movePointer(-POINTER_SPEED, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showPointer()
                movePointer(POINTER_SPEED, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Simulate click event on WebView at pointer position
                performClick()
                return true
            }
        }
        return false
    }

    private fun movePointer(deltaX: Float, deltaY: Float) {
        // Update pointer position
        pointerX += deltaX
        pointerY += deltaY

        // Scroll the WebView if the pointer moves beyond the visible bounds
        // Horizontal scrolling
        if (pointerX < SCROLL_THRESHOLD && webView.scrollX > 0) {
            val newScrollX = (webView.scrollX - SCROLL_AMOUNT).coerceAtLeast(0)
            webView.scrollTo(newScrollX, webView.scrollY)
            pointerX = SCROLL_THRESHOLD // Keep the pointer near the edge
            println("Scrolled left: scrollX=${webView.scrollX}")
        } else if (pointerX > container.width - pointer.width - SCROLL_THRESHOLD && contentWidth > 0) {
            val maxScrollX = (contentWidth * webView.scaleX - container.width).toInt()
            val newScrollX = (webView.scrollX + SCROLL_AMOUNT).coerceAtMost(maxScrollX.coerceAtLeast(0))
            webView.scrollTo(newScrollX, webView.scrollY)
            pointerX = (container.width - pointer.width - SCROLL_THRESHOLD).coerceAtLeast(0f)
            println("Scrolled right: scrollX=${webView.scrollX}")
        }

        // Vertical scrolling
        if (pointerY < SCROLL_THRESHOLD && webView.scrollY > 0) {
            val newScrollY = (webView.scrollY - SCROLL_AMOUNT).coerceAtLeast(0)
            webView.scrollTo(webView.scrollX, newScrollY)
            pointerY = SCROLL_THRESHOLD // Keep the pointer near the edge
            println("Scrolled up: scrollY=${webView.scrollY}")
        } else if (pointerY > container.height - pointer.height - SCROLL_THRESHOLD && contentHeight > 0) {
            val maxScrollY = (contentHeight * webView.scaleY - container.height).toInt()
            val newScrollY = (webView.scrollY + SCROLL_AMOUNT).coerceAtMost(maxScrollY.coerceAtLeast(0))
            webView.scrollTo(webView.scrollX, newScrollY)
            pointerY = (container.height - pointer.height - SCROLL_THRESHOLD).coerceAtLeast(0f)
            println("Scrolled down: scrollY=${webView.scrollY}")
        }

        // Keep pointer within bounds
        pointerX = pointerX.coerceIn(0f, container.width.toFloat() - pointer.width)
        pointerY = pointerY.coerceIn(0f, container.height.toFloat() - pointer.height)

        updatePointerPosition()
        resetPointerHideTimer()
    }

    private fun updatePointerPosition() {
        pointer.x = pointerX
        pointer.y = pointerY
        println("Pointer position updated: x=$pointerX, y=$pointerY")
    }

    private fun showPointer() {
        pointer.visibility = View.VISIBLE
        println("Pointer shown")
        resetPointerHideTimer()
    }

    private fun hidePointer() {
        pointer.visibility = View.INVISIBLE
        println("Pointer hidden")
    }

    private fun resetPointerHideTimer() {
        pointerHideHandler.removeCallbacksAndMessages(null)
        println("Pointer hide timer reset")
        pointerHideHandler.postDelayed({ hidePointer() }, POINTER_HIDE_DELAY)
    }

    private fun performClick() {
        println("Performing click at: x=$pointerX, y=$pointerY")
        showPointer()

        // Calculate the coordinates within the WebView
        val x = pointerX.toInt()
        val y = pointerY.toInt()

        // Log focus state before dispatching touch events
        println("Before dispatchTouchEvent: containerHasFocus=${container.hasFocus()}")

        // Create and dispatch touch events
        val downTime = System.currentTimeMillis()
        val eventTime = downTime + 100

        // Create DOWN event
        val downEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0
        )

        // Create UP event
        val upEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
        )

        // Dispatch events to WebView
        webView.dispatchTouchEvent(downEvent)
        webView.dispatchTouchEvent(upEvent)

        // Log focus state after dispatching touch events
        println("After dispatchTouchEvent: containerHasFocus=${container.hasFocus()}")

        // Restore focus to the container
        container.requestFocus()
        println("Focus restored to container after click")

        // Recycle the events
        downEvent.recycle()
        upEvent.recycle()

        resetPointerHideTimer()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        // Pause any playing videos
        webView.evaluateJavascript(
            """
            (function() {
                var videos = document.getElementsByTagName('video');
                for (var i = 0; i < videos.length; i++) {
                    videos[i].pause();
                }
            })();
            """, null
        )
        println("WebViewFragment: onPause called, WebView paused")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        container.requestFocus()
        println("WebViewFragment: onResume called, WebView resumed")
    }

    override fun onDestroy() {
        super.onDestroy()
        pointerHideHandler.removeCallbacksAndMessages(null)

        // Exit fullscreen mode if active
        if (isInFullscreen) {
            (webView.webChromeClient as WebChromeClient).onHideCustomView()
        }

        // Stop and destroy the WebView
        webView.stopLoading() // Stop any ongoing loading
        webView.evaluateJavascript(
            """
            (function() {
                var videos = document.getElementsByTagName('video');
                for (var i = 0; i < videos.length; i++) {
                    videos[i].pause();
                }
            })();
            """, null
        )
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank") // Load a blank page to stop any activity
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()

        // Clean up the fullscreen container
        fullscreenContainer.removeAllViews()
        activity?.findViewById<ViewGroup>(android.R.id.content)?.removeView(fullscreenContainer)

        println("WebViewFragment: onDestroy called, WebView destroyed")
    }

    // Handle back button press
    fun onBackPressed(): Boolean {
        // If in fullscreen mode, exit fullscreen
        if (isInFullscreen) {
            (webView.webChromeClient as WebChromeClient).onHideCustomView()
            return true
        }
        // If can go back in WebView history
        return if (webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }
}