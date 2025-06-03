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
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.json.JSONObject
import kotlin.math.max

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
    private var bottomNavHeight: Float = 0f
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var isInFullscreen = false
    private var lastDimensionUpdate = 0L
    private val dimensionUpdateDebounce = 1000L
    private val jsHandler = Handler(Looper.getMainLooper())

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

        originalOrientation = requireActivity().requestedOrientation

        fullscreenContainer = FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        requireActivity().findViewById<ViewGroup>(android.R.id.content)?.addView(fullscreenContainer)

        setupPointer(view)
        setupWebView(url)

        container.isFocusable = true
        container.isFocusableInTouchMode = true
        container.requestFocus()
        container.setOnKeyListener { _, keyCode, event ->
            println("Key event received: keyCode=$keyCode, action=${event.action}, containerHasFocus=${container.hasFocus()}")
            handleKeyEvent(keyCode, event)
        }

        container.setOnFocusChangeListener { _, hasFocus ->
            println("Container focus changed: hasFocus=$hasFocus")
        }
    }

    private fun setupPointer(view: View) {
        pointer = ImageView(context).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.cursor)
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(48, 48)
        }

        (view as ViewGroup).addView(pointer)

        view.post {
            pointerX = (container.width / 2).toFloat()
            pointerY = (container.height / 2).toFloat()
            updatePointerPosition()
            println("Pointer initialized: x=$pointerX, y=$pointerY, containerHeight=${container.height}")
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
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            allowFileAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowContentAccess = true
            setSupportZoom(true)
        }

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.keepScreenOn = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateContentDimensions()
                injectFullscreenFix()
                injectMutationObserver()
                println("Page finished loading: $url")
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                println("WebViewFragment: Error loading page: $description (code: $errorCode)")
                showToast("Failed to load page: $description")
                handler.postDelayed({
                    parentFragmentManager.popBackStack()
                }, 2000L) // Delay to show toast
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                println("onShowCustomView called")
                if (customView != null) {
                    onHideCustomView()
                    return
                }

                isInFullscreen = true
                customView = view
                customViewCallback = callback

                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

                fullscreenContainer.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))

                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.INVISIBLE
                container.visibility = View.INVISIBLE

                hidePointer()

                println("Entered fullscreen mode")
            }

            override fun onHideCustomView() {
                println("onHideCustomView called")
                if (customView == null) return

                isInFullscreen = false

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

                requireActivity().requestedOrientation = originalOrientation

                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                container.visibility = View.VISIBLE

                fullscreenContainer.removeView(customView)

                customViewCallback?.onCustomViewHidden()

                customView = null
                customViewCallback = null

                container.requestFocus()
                updateContentDimensions()
                println("Exited fullscreen mode")
            }
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.loadUrl(url)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
    }

    private fun injectFullscreenFix() {
        val js = """
            (function() {
                var videoElements = document.getElementsByTagName('video');
                for (var i = 0; i < videoElements.length; i++) {
                    videoElements[i].addEventListener('webkitbeginfullscreen', function() {
                        console.log('Video entered fullscreen');
                    });
                    videoElements[i].addEventListener('play', function() {
                        console.log('Video playback started');
                    });
                    videoElements[i].addEventListener('error', function(e) {
                        console.log('Video playback error: ' + e.message);
                    });
                }
                var style = document.createElement('style');
                style.textContent = 'video { transform: translateZ(0); }';
                document.head.appendChild(style);
            })();
        """.trimIndent()

        jsHandler.post { webView.evaluateJavascript(js, null) }
    }

    private fun injectInteractionFix() {
        val js = """
            (function() {
                function applyInteractionStyles(element) {
                    if (!element.style.zIndex || parseInt(element.style.zIndex) < 1000) {
                        element.style.setProperty('pointer-events', 'auto', 'important');
                        element.style.setProperty('z-index', '1000', 'important');
                    }
                }
                var elements = document.querySelectorAll('a[href], button, [role="button"], [onclick], [class*="page"], [class*="nav"] a');
                for (var i = 0; i < elements.length; i++) {
                    applyInteractionStyles(elements[i]);
                }
            })();
        """.trimIndent()
        jsHandler.post { webView.evaluateJavascript(js, null) }
    }

    private fun injectMutationObserver() {
        val js = """
            (function() {
                const observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        if (mutation.type === 'childList' || mutation.type === 'attributes') {
                            window.AndroidBridge.updateDimensions();
                        }
                    });
                });
                observer.observe(document.body, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['style', 'class']
                });
            })();
        """.trimIndent()
        jsHandler.post { webView.evaluateJavascript(js, null) }
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
                        println("Content dimensions updated due to DOM change")
                    }
                }
            }
        }
    }

    private fun updateContentDimensions() {
        webView.evaluateJavascript(
            """
            (function() {
                const originalScrollY = window.scrollY;
                window.scrollTo(0, Number.MAX_SAFE_INTEGER);
                const maxScrollY = window.scrollY;
                window.scrollTo(0, originalScrollY);

                let maxHeight = Math.max(
                    document.body.scrollHeight,
                    document.documentElement.scrollHeight,
                    document.body.offsetHeight,
                    document.documentElement.offsetHeight,
                    window.innerHeight,
                    maxScrollY + window.innerHeight
                );
                let maxWidth = Math.max(
                    document.body.scrollWidth,
                    document.documentElement.scrollWidth,
                    document.body.offsetWidth,
                    document.documentElement.offsetWidth,
                    window.innerWidth
                );

                const elements = document.querySelectorAll('[style*="position"], nav, footer, [role="navigation"]');
                let fixedHeight = 0;
                let bottomNavHeight = 0;
                let fixedElementsDetails = [];
                for (let el of elements) {
                    const style = window.getComputedStyle(el);
                    if ((style.position === 'fixed' || style.position === 'sticky') && style.display !== 'none' && style.visibility !== 'hidden') {
                        const rect = el.getBoundingClientRect();
                        const elBottom = rect.bottom + window.scrollY;
                        maxHeight = Math.max(maxHeight, elBottom);
                        fixedHeight = Math.max(fixedHeight, rect.height);
                        const bottomValue = parseFloat(style.bottom) || 0;
                        if ((el.tagName === 'DIV' || el.tagName === 'NAV') && bottomValue >= 0 && bottomValue < 50 && rect.height < 100) {
                            bottomNavHeight = Math.max(bottomNavHeight, rect.height);
                        }
                        fixedElementsDetails.push({
                            tag: el.tagName,
                            height: rect.height,
                            bottom: rect.bottom,
                            position: style.position,
                            styleBottom: style.bottom,
                            zIndex: style.zIndex,
                            class: el.className
                        });
                    }
                }

                return {
                    width: maxWidth,
                    height: maxHeight,
                    fixedHeight: fixedHeight,
                    bottomNavHeight: bottomNavHeight,
                    fixedElementsDetails: fixedElementsDetails
                };
            })();
            """.trimIndent(),
            ValueCallback { value ->
                var contentHeightFromWebView = (webView.contentHeight * webView.scaleY).toInt()
                try {
                    val json = JSONObject(value)
                    contentWidth = json.getInt("width")
                    contentHeight = json.getInt("height")
                    val fixedHeight = json.getInt("fixedHeight")
                    bottomNavHeight = json.getInt("bottomNavHeight").toFloat()
                    val fixedElementsDetails = json.getJSONArray("fixedElementsDetails")
                    contentHeightFromWebView = (webView.contentHeight * webView.scaleY).toInt()
                    if (contentHeight > contentHeightFromWebView + 500) {
                        contentHeight = contentHeightFromWebView
                        println("Content height capped to contentHeightFromWebView: $contentHeight")
                    }
                    println("Content dimensions updated: width=$contentWidth, height=$contentHeight, fixedHeight=$fixedHeight, bottomNavHeight=$bottomNavHeight, fixedElementsDetails=$fixedElementsDetails, scrollY=${webView.scrollY}, contentHeightFromWebView=$contentHeightFromWebView, scaleY=${webView.scaleY}, containerHeight=${container.height}")
                } catch (e: Exception) {
                    contentWidth = webView.width
                    contentHeight = contentHeightFromWebView
                    bottomNavHeight = 0f
                    println("Failed to parse content dimensions: ${e.message}, using defaults: width=$contentWidth, height=$contentHeight, contentHeightFromWebView=$contentHeightFromWebView")
                }
            }
        )
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        println("Handling key event: keyCode=$keyCode, action=${event.action}")
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        if (isInFullscreen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    webView.evaluateJavascript(
                        """
                        (function() {
                            var videos = document.getElementsByTagName('video');
                            if (videos.length > 0) {
                                if (videos[0].paused) {
                                    videos[0].play();
                                    return 'play';
                                } else {
                                    videos[0].pause();
                                    return 'pause';
                                }
                            }
                            return 'no_video';
                        })()
                        """.trimIndent(),
                        ValueCallback { value ->
                            println("Fullscreen video action: $value")
                        }
                    )
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    webView.webChromeClient?.onHideCustomView()
                    return true
                }
            }
            return false
        }

        if (!container.hasFocus()) {
            container.requestFocus()
            println("Focus restored to container in handleKeyEvent")
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                showPointer()
                movePointer(0f, -pointerSpeed)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showPointer()
                movePointer(0f, pointerSpeed)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showPointer()
                movePointer(-pointerSpeed, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showPointer()
                movePointer(pointerSpeed, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                performClick()
                return true
            }
            else -> {
                println("Unhandled key event: keyCode=$keyCode")
                return false
            }
        }
    }

    private fun movePointer(deltaX: Float, deltaY: Float) {
        pointerX += deltaX
        pointerY += deltaY

        val contentHeightFromWebView = (webView.contentHeight * webView.scaleY).toInt()
        var interactiveElement = ""
        var interactiveElementY = 0f
        var interactiveElementX = 0f
        val adjustedY = (pointerY + webView.scrollY).coerceIn(0f, contentHeight.toFloat())
        webView.evaluateJavascript(
            """
            (function() {
                var x = ${pointerX.toInt()};
                var y = ${adjustedY.toInt()};
                var selectors = ['a[href]', 'button', '[role="button"]', '[onclick]', '[class*="play"]', '[class*="pause"]', '[class*="video"]', '[class*="nav"] a'];
                for (var selector of selectors) {
                    var elements = document.querySelectorAll(selector);
                    for (var el of elements) {
                        var rect = el.getBoundingClientRect();
                        if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom &&
                            el.offsetWidth > 0 && el.offsetHeight > 0) {
                            return el.tagName + '|' + el.className + '|' + rect.top + '|' + ((rect.left + rect.right) / 2);
                        }
                    }
                }
                return '';
            })();
            """.trimIndent(),
            ValueCallback { value ->
                try {
                    if (value.isNotEmpty() && value != "\"\"") {
                        val parts = value.replace("\"", "").split("|")
                        if (parts.size >= 4) {
                            interactiveElement = "${parts[0]}|${parts[1]}"
                            interactiveElementY = parts[2].toFloatOrNull() ?: 0f
                            interactiveElementX = parts[3].toFloatOrNull() ?: pointerX
                        }
                    }
                    println("Interactive element check at x=$pointerX, y=$pointerY, adjustedY=$adjustedY, element=$interactiveElement, elementY=$interactiveElementY, elementX=$interactiveElementX, rawValue=$value")
                } catch (e: Exception) {
                    println("Failed to parse interactive element: ${e.message}, value=$value")
                }
            }
        )

        var hoverAttempt = 0
        fun tryHover() {
            webView.evaluateJavascript(
                """
                (function() {
                    var x = ${pointerX.toInt()};
                    var y = ${adjustedY.toInt()};
                    var element = document.elementFromPoint(x, y);
                    if (element && element.offsetWidth > 0 && element.offsetHeight > 0) {
                        var events = [
                            new MouseEvent('mouseover', { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y }),
                            new MouseEvent('mouseenter', { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y }),
                            new MouseEvent('mousemove', { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y }),
                            new PointerEvent('pointerover', { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y }),
                            new PointerEvent('pointerenter', { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y }),
                            new PointerEvent('pointermove', { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y })
                        ];
                        events.forEach(event => element.dispatchEvent(event));
                        return element.tagName + '|' + element.className;
                    }
                    return '';
                })();
                """.trimIndent(),
                ValueCallback { value ->
                    if (value == "\"\"" && hoverAttempt < 2) {
                        hoverAttempt++
                        jsHandler.postDelayed({ tryHover() }, 50)
                    } else {
                        println("Hover events simulated at x=$pointerX, y=$pointerY, adjustedY=$adjustedY, element=$value, attempt=$hoverAttempt")
                    }
                }
            )
        }
        jsHandler.post { tryHover() }

        // Horizontal scrolling
        if (deltaX < 0 && pointerX < scrollThreshold && webView.scrollX > 0) {
            val newScrollX = (webView.scrollX - pointerSpeed.toInt()).coerceAtLeast(0)
            webView.scrollTo(newScrollX, webView.scrollY)
            pointerX = scrollThreshold
            println("Scrolled left: scrollX=${webView.scrollX}, pointerX=$pointerX, deltaX=$deltaX")
        } else if (deltaX > 0 && pointerX > container.width - pointer.width - scrollThreshold && contentWidth > 0) {
            val maxScrollX = (contentWidth * webView.scaleX - container.width).toInt().coerceAtLeast(0)
            val newScrollX = (webView.scrollX + pointerSpeed.toInt()).coerceAtMost(maxScrollX)
            webView.scrollTo(newScrollX, webView.scrollY)
            pointerX = (container.width.toFloat() - pointer.width.toFloat() - scrollThreshold).coerceAtLeast(0f)
            println("Scrolled right: scrollX=${webView.scrollX}, pointerX=$pointerX, deltaX=$deltaX")
        }

        // Vertical scrolling
        val calculatedMaxScrollY = (contentHeight - container.height).toInt().coerceAtLeast(0)
        val maxScrollY = maxOf(calculatedMaxScrollY, contentHeightFromWebView)
        if (deltaY < 0 && webView.scrollY > 0) {
            val newScrollY = (webView.scrollY - pointerSpeed.toInt()).coerceAtLeast(0)
            webView.scrollTo(webView.scrollX, newScrollY)
            pointerY = (pointerY + deltaY).coerceAtLeast(0f)
            if (interactiveElement.isNotEmpty()) {
                pointerY = (interactiveElementY - webView.scrollY).coerceAtLeast(0f).coerceAtMost(container.height.toFloat() - pointer.height.toFloat())
                pointerX = interactiveElementX.coerceAtLeast(0f).coerceAtMost(container.width.toFloat() - pointer.width.toFloat())
                println("Adjusted pointer for interactive element: pointerX=$pointerX, pointerY=$pointerY, interactiveElement=$interactiveElement")
            }
            println("Scrolled up: scrollY=${webView.scrollY}, pointerX=$pointerX, pointerY=$pointerY, deltaY=$deltaY")
            updateContentDimensions()
        } else if (deltaY > 0) {
            val newScrollY = (webView.scrollY + pointerSpeed.toInt()).coerceAtMost(maxScrollY)
            webView.scrollTo(webView.scrollX, newScrollY)
            pointerY = (pointerY + deltaY).coerceAtMost(container.height.toFloat() - pointer.height.toFloat())
            if (interactiveElement.isNotEmpty()) {
                pointerY = (interactiveElementY - webView.scrollY).coerceAtLeast(0f).coerceAtMost(container.height.toFloat() - pointer.height.toFloat())
                pointerX = interactiveElementX.coerceAtLeast(0f).coerceAtMost(container.width.toFloat() - pointer.width.toFloat())
                println("Adjusted pointer for interactive element: pointerX=$pointerX, pointerY=$pointerY, interactiveElement=$interactiveElement")
            }
            println("Scrolled down: scrollY=${webView.scrollY}, pointerX=$pointerX, pointerY=$pointerY, maxScrollY=$maxScrollY, contentHeight=$contentHeight, contentHeightFromWebView=$contentHeightFromWebView, deltaY=$deltaY")
            updateContentDimensions()
        }

        pointerX = pointerX.coerceIn(0f, container.width.toFloat() - pointer.width.toFloat())
        pointerY = pointerY.coerceIn(0f, container.height.toFloat() - pointer.height.toFloat())

        updatePointerPosition()
        resetPointerHideTimer()
    }

    private fun updatePointerPosition() {
        pointer.x = pointerX
        pointer.y = pointerY
        pointer.visibility = View.VISIBLE
        println("Pointer position updated: x=$pointerX, y=$pointerY, visibility=${pointer.visibility}")
    }

    private fun showPointer() {
        pointer.visibility = View.VISIBLE
        println("Pointer shown: visibility=${pointer.visibility}")
        resetPointerHideTimer()
    }

    private fun hidePointer() {
        pointer.visibility = View.INVISIBLE
        println("Pointer hidden")
    }

    private fun resetPointerHideTimer() {
        pointerHideHandler.removeCallbacksAndMessages(null)
        pointerHideHandler.postDelayed({ hidePointer() }, pointerHideDelay)
    }

    private fun performClick() {
        println("Performing click at: x=$pointerX, y=$pointerY, scrollY=${webView.scrollY}")
        showPointer()

        val x = pointerX.toInt()
        val adjustedY = (pointerY + webView.scrollY).toInt()

        webView.evaluateJavascript(
            """
            (function() {
                var x = $x;
                var y = $adjustedY;
                var element = document.elementFromPoint(x, y);
                if (element && element.offsetWidth > 0 && element.offsetHeight > 0) {
                    var events = [
                        new MouseEvent('mousedown', { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y }),
                        new MouseEvent('mouseup', { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y }),
                        new MouseEvent('click', { view: window, bubbles: true, cancelable: true, clientX: x, clientY: y })
                    ];
                    events.forEach(event => element.dispatchEvent(event));
                    return element.tagName + '|' + element.className;
                }
                return 'No element found';
            })();
            """.trimIndent(),
            ValueCallback { value ->
                println("Click simulated: x=$x, y=$adjustedY, element=$value")
                if (value == "\"No element found\"") {
                    println("Click failed: No interactive element at x=$x, y=$adjustedY")
                }
            }
        )

        val downTime = System.currentTimeMillis()
        val eventTime = downTime + 100
        val downEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_DOWN, x.toFloat(), pointerY.toFloat(), 0
        )
        val upEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_UP, x.toFloat(), pointerY.toFloat(), 0
        )

        webView.dispatchTouchEvent(downEvent)
        webView.dispatchTouchEvent(upEvent)

        container.requestFocus()
        println("Focus restored to container after click")

        downEvent.recycle()
        upEvent.recycle()

        resetPointerHideTimer()
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
        updateContentDimensions()
        println("WebViewFragment: onResume called, WebView resumed")
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.keepScreenOn = false
        pointerHideHandler.removeCallbacksAndMessages(null)
        jsHandler.removeCallbacksAndMessages(null)

        if (isInFullscreen) {
            webView.webChromeClient?.onHideCustomView()
        }

        webView.stopLoading()
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
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()

        fullscreenContainer.removeAllViews()
        requireActivity().findViewById<ViewGroup>(android.R.id.content)?.removeView(fullscreenContainer)

        println("WebViewFragment: onDestroy called, WebView destroyed")
    }

    fun onBackPressed(): Boolean {
        if (isInFullscreen) {
            webView.webChromeClient?.onHideCustomView()
            return true
        }
        return if (webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }

    companion object {
        private val handler = Handler(Looper.getMainLooper())
    }
}