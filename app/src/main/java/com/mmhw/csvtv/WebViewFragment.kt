package com.mmhw.csvtv

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.app.AlertDialog
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import org.json.JSONObject
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
    private lateinit var btnWebAdblockToggle: ImageButton
    private lateinit var btnWebHome: ImageButton
    private lateinit var webToolbar: View
    private lateinit var webProgress: ProgressBar
    private var isToolbarShowing = false
    private var isBrowserCard = false
    private var urlEditText: EditText? = null
    private var isWebViewDestroyed = false
    private var isAdblockEnabled = true
    private var initialLoadCompleted = false
    private val pointerHideHandler = Handler(Looper.getMainLooper())
    private val pointerHideDelay = 3000L
    private var pointerX = 0f
    private var pointerY = 0f
    private val pointerSpeed = 20f
    private val scrollThreshold = 30f

    // Track if we recently showed keyboard for a web input via pointer click.
    // Used to avoid immediately stealing focus back to container (which would hide IME).
    private var webInputKeyboardShown = false
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

    // When a non-browser WebView loads an embed page that fetches HLS, hand off to native player once.
    private var handedOffToNativePlayer = false
    /**
     * Stream URL we just handed off / returned from. Suppress auto re-handoff of the same
     * dead source when the page keeps requesting it (HLS playlist poll, HTML video retry).
     * Cleared on navigation to a different page or explicit refresh.
     */
    private var suppressHandoffUrl: String? = null
    private var lastWebPageUrl: String? = null

    // HTML5 video stall recovery (WebView has no ExoPlayer stall detector)
    private val videoStallHandler = Handler(Looper.getMainLooper())
    private var lastHtmlVideoTime = -1.0
    private var htmlVideoStallTicks = 0
    private var htmlVideoRecoverCount = 0
    private val maxHtmlVideoRecovers = 6
    private val htmlVideoStallIntervalMs = 3000L
    private val htmlVideoStallThreshold = 4 // ~12s frozen
    private val videoStallCheckRunnable = object : Runnable {
        override fun run() {
            checkHtmlVideoStall()
            if (!isWebViewDestroyed && isAdded) {
                videoStallHandler.postDelayed(this, htmlVideoStallIntervalMs)
            }
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_webview, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = arguments?.getString("url") ?: return
        isBrowserCard = arguments?.getBoolean("is_browser_card", false) ?: false
        webView = view.findViewById(R.id.web_view)
        container = view.findViewById(R.id.webview_container)

        val activityContent = requireActivity().findViewById<ViewGroup>(android.R.id.content)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (!imeVisible && webInputKeyboardShown) {
                webInputKeyboardShown = false
                webView.isFocusable = false
                webView.isFocusableInTouchMode = false
                webView.clearFocus()
                if (isAdded) {
                    container.requestFocus()
                    setCursorVisibility(true)
                }
            }
            insets
        }

        // Initialize fullscreen and pointer containers EARLY so that setCursorVisibility
        // and updateToolbarButtonsFocus can safely access the lateinit properties.
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

        webToolbar = view.findViewById(R.id.web_toolbar)
        // Use SOFTWARE for toolbar overlay to avoid EGL sync conflicts with WebView
        webToolbar.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        btnWebBack = view.findViewById(R.id.btn_web_back)
        btnWebForward = view.findViewById(R.id.btn_web_forward)
        btnWebRefresh = view.findViewById(R.id.btn_web_refresh)
        btnWebDesktopToggle = view.findViewById(R.id.btn_web_desktop_toggle)
        btnWebAutoFullscreen = view.findViewById(R.id.btn_web_auto_fullscreen)
        btnWebClose = view.findViewById(R.id.btn_web_close)
        btnWebAdblockToggle = view.findViewById(R.id.btn_web_adblock_toggle)
        btnWebHome = view.findViewById(R.id.btn_web_home)
        webProgress = view.findViewById(R.id.web_progress)

        btnWebAdblockToggle.visibility = if (isBrowserCard) View.VISIBLE else View.GONE
        btnWebHome.visibility = if (isBrowserCard) View.VISIBLE else View.GONE

        btnWebClose.setOnClickListener {
            exitToMain()
        }

        // Align system Back with D-pad Back: FS → history → main (same as Close at root).
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackNavigation()
                }
            }
        )

        if (isBrowserCard) {
            updateAdblockIcon()
            btnWebAdblockToggle.setOnClickListener {
                isAdblockEnabled = !isAdblockEnabled
                updateAdblockIcon()
                showToast("Adblock ${if (isAdblockEnabled) "enabled" else "disabled"}")
            }
        }

        if (isBrowserCard) {
            setupBrowserUrlBar(view, url)
            // Do not force toolbar visible on load. This keeps the pointer/cursor working
            // on the page content immediately. User moves the pointer up (or uses D-pad nav)
            // to show the toolbar and select the URL bar "button", then press center/click
            // to open the input dialog.
        }

        // Ensure pointer/cursor is active when loading the browser.
        // Now safe because pointer/pointerContainer are initialized above.
        if (!isInFullscreen) {
            setCursorVisibility(true)
        }

        btnWebBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
                updateNavigationButtons()
            }
        }
        btnWebForward.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
                updateNavigationButtons()
            }
        }
        btnWebRefresh.setOnClickListener {
            // Explicit refresh: allow re-handoff of a previously failed stream.
            suppressHandoffUrl = null
            handedOffToNativePlayer = false
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

        btnWebHome.setOnClickListener {
            goHome()
        }

        originalOrientation = requireActivity().requestedOrientation

        setupWebView(url)

        container.isFocusable = true
        container.isFocusableInTouchMode = true
        if (!webInputKeyboardShown) {
            container.requestFocus()
        }

        updateNavigationButtons()

        val buttonKeyListener = View.OnKeyListener { _, keyCode, event ->
            handleKeyEvent(keyCode, event)
        }

        // Set general key listener for all toolbar buttons for uniform D-pad handling (including adblock and URL bar)
        val toolbarKeyButtons = listOf(
            btnWebBack, btnWebForward, btnWebRefresh, btnWebHome,
            btnWebAutoFullscreen, btnWebDesktopToggle, btnWebAdblockToggle, btnWebClose
        )
        for (btn in toolbarKeyButtons) {
            btn.setOnKeyListener(buttonKeyListener)
        }
        if (urlEditText != null) {
            urlEditText!!.setOnKeyListener(buttonKeyListener)
        }

        container.setOnKeyListener { _, keyCode, event ->
            handleKeyEvent(keyCode, event)
        }
    }

    private fun setupBrowserUrlBar(view: View, initialUrl: String) {
        val urlBarEdit = view.findViewById<EditText>(R.id.browser_url_edit)
        if (urlBarEdit == null) return

        urlBarEdit.visibility = View.VISIBLE
        urlBarEdit.setText(initialUrl)
        urlBarEdit.isFocusable = true
        urlBarEdit.isFocusableInTouchMode = true
        urlBarEdit.isClickable = true
        // Make it non-editable directly (acts like a button)
        urlBarEdit.keyListener = null
        urlBarEdit.setCursorVisible(false)
        urlEditText = urlBarEdit

        // Prevent system clipboard/autofill that can log "not in focus" denials
        urlBarEdit.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            urlBarEdit.setAutofillHints()
        }

        urlBarEdit.setBackgroundColor(0x33FFFFFF.toInt())

        // Hover/focus effect like other buttons (brighten on focus for TV)
        urlBarEdit.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            urlBarEdit.setBackgroundColor(if (hasFocus) 0x55FFFFFF.toInt() else 0x33FFFFFF.toInt())
        }

        val openDialog = {
            showUrlInputDialog(urlBarEdit.text.toString())
        }

        urlBarEdit.setOnClickListener {
            openDialog()
        }
    }

    private fun isAdUrl(url: String): Boolean {
        if (!isAdblockEnabled) return false
        val adDomains = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "facebook.com/tr", "adservice.google", "adnxs.com", "advertising.com",
            "scorecardresearch.com", "quantserve.com", "chartbeat.com", "outbrain.com",
            "taboola.com", "criteo.com", "adsafeprotected.com"
        )
        val lower = url.lowercase()
        return adDomains.any { lower.contains(it) }
    }

    private fun updateAdblockIcon() {
        val ctx = btnWebAdblockToggle.context
        val onId = ctx.resources.getIdentifier("ic_speaker_notes", "drawable", ctx.packageName)
        val offId = ctx.resources.getIdentifier("ic_speaker_notes_off", "drawable", ctx.packageName)
        if (isAdblockEnabled) {
            val res = if (onId != 0) onId else R.drawable.ic_check_circle
            btnWebAdblockToggle.setImageResource(res)
            btnWebAdblockToggle.tooltipText = "Adblock enabled (click to disable)"
        } else {
            val res = if (offId != 0) offId else R.drawable.ic_cancel_circle
            btnWebAdblockToggle.setImageResource(res)
            btnWebAdblockToggle.tooltipText = "Adblock disabled (click to enable)"
        }
    }

    private fun updateNavigationButtons() {
        val canBack = webView.canGoBack()
        val canForward = webView.canGoForward()
        btnWebBack.isEnabled = canBack
        btnWebForward.isEnabled = canForward
        btnWebBack.alpha = if (canBack) 1f else 0.5f
        btnWebForward.alpha = if (canForward) 1f else 0.5f
    }

    private fun showUrlInputDialog(currentUrl: String) {
        if (!isBrowserCard || isWebViewDestroyed) return
        val ctx = requireContext()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setText(currentUrl)
            setSelection(currentUrl.length)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val margin = (16 * ctx.resources.displayMetrics.density).toInt()
            lp.setMargins(margin, margin, margin, margin)
            layoutParams = lp
            // Prevent system clipboard/autofill access that can trigger "not in focus" denials
            // especially during complex focus states in browser
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setAutofillHints()
            }
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Enter URL or search")
            .setView(container)
            .setPositiveButton("Go") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val urlToLoad = if (Utils.isUrlLike(text)) {
                        if (!text.startsWith("http://") && !text.startsWith("https://")) {
                            "https://$text"
                        } else text
                    } else {
                        Utils.buildSearchUrl(Utils.getDefaultBrowserPage(ctx), text)
                    }
                    webView.loadUrl(urlToLoad)
                    urlEditText?.setText(urlToLoad)
                    updateNavigationButtons()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
                true
            } else {
                false
            }
        }

        dialog.show()
        input.post {
            input.requestFocus()
            input.selectAll()
        }
    }

    private fun setupPointer(parent: ViewGroup) {
        pointer = ImageView(context).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.cursor)
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(36, 36)
            // Use SOFTWARE layer for the cursor overlay. This prevents Chromium/WebView from
            // failing to create EGL fence sync objects (EGL_BAD_ATTRIBUTE) when hardware
            // layers are mixed with overlays on emulators.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        parent.addView(pointer)

        // Set initial position immediately (post will refine after layout)
        pointerX = (parent.width / 2).toFloat()
        pointerY = (parent.height / 2).toFloat()
        updatePointerPosition()

        parent.post {
            if (parent.width > 0 && parent.height > 0) {
                pointerX = (parent.width / 2).toFloat()
                pointerY = (parent.height / 2).toFloat()
                updatePointerPosition()
            }
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
            // Disable offscreen pre-raster to reduce EGL fence sync issues on some devices/emulators
            offscreenPreRaster = false
        }

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.keepScreenOn = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
                // Navigating to a different page: allow handoff of streams again.
                if (!url.isNullOrBlank() &&
                    !url.startsWith("about:") &&
                    url != lastWebPageUrl
                ) {
                    lastWebPageUrl = url
                    suppressHandoffUrl = null
                    handedOffToNativePlayer = false
                }
                activity?.runOnUiThread {
                    if (isAdded) {
                        btnWebRefresh.setImageResource(R.drawable.ic_close)
                        if (isBrowserCard) {
                            // Early block clipboard to avoid "not in focus" denials during load
                            webView.evaluateJavascript("""
                                (function() {
                                    try {
                                        if (navigator.clipboard) {
                                            const blocked = () => Promise.reject(new Error('Clipboard disabled in this browser'));
                                            navigator.clipboard.readText = blocked;
                                            navigator.clipboard.writeText = blocked;
                                            navigator.clipboard.read = blocked;
                                            navigator.clipboard.write = blocked;
                                        }
                                        const origExec = document.execCommand;
                                        document.execCommand = function(cmd, ...args) {
                                            if (['copy', 'cut', 'paste'].includes(cmd)) {
                                                console.log('Blocked clipboard via execCommand: ' + cmd);
                                                return false;
                                            }
                                            return origExec.apply(this, arguments);
                                        };
                                    } catch(e) {}
                                })();
                            """, null);
                        }
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
                        if (isBrowserCard && urlEditText != null) {
                            urlEditText?.setText(url ?: "")
                        }
                        updateNavigationButtons()

                        if (isBrowserCard) {
                            // Block clipboard access from the page to avoid "not in focus" denials
                            // and repeated system errors on TV. Pages like search engines often try it.
                            webView.evaluateJavascript("""
                                (function() {
                                    try {
                                        if (navigator.clipboard) {
                                            const blocked = () => Promise.reject(new Error('Clipboard disabled in this browser'));
                                            navigator.clipboard.readText = blocked;
                                            navigator.clipboard.writeText = blocked;
                                            navigator.clipboard.read = blocked;
                                            navigator.clipboard.write = blocked;
                                        }
                                        const origExec = document.execCommand;
                                        document.execCommand = function(cmd, ...args) {
                                            if (['copy', 'cut', 'paste'].includes(cmd)) {
                                                console.log('Blocked clipboard via execCommand: ' + cmd);
                                                return false;
                                            }
                                            return origExec.apply(this, arguments);
                                        };
                                    } catch(e) {}
                                })();
                            """, null);
                        }

                        // Reset pointer after new page load so pointer works on new URL
                        if (!isInFullscreen) {
                            setCursorVisibility(true);
                            if (::pointerContainer.isInitialized && pointerContainer.width > 0 && pointerContainer.height > 0) {
                                pointerX = pointerContainer.width / 2f;
                                pointerY = pointerContainer.height / 2f;
                                updatePointerPosition();
                            }
                        }
                    }
                }

                updateContentDimensions()
                // Stream opened via WebView: keep HTML5 video alive (autoplay + stall recovery)
                if (!isBrowserCard) {
                    tryPlayHtmlVideo()
                    startHtmlVideoStallMonitor()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val isRedirect = request.isRedirect
                val hasGesture = request.hasGesture()

                // Non-browser: navigating to a direct stream → native player (has reconnect)
                if (!isBrowserCard && Utils.isVideoStream(url, null)) {
                    handOffToNativePlayer(url)
                    return true
                }

                // Allow initial page load redirections without alerts
                if (!initialLoadCompleted) {
                    return false
                }

                // Auto-block ad related redirects/force redirects
                if (isAdUrl(url)) {
                    return true
                }

                // Allow standard explicit clicks (hasGesture = true and not an HTTP redirect)
                if (hasGesture && !isRedirect) {
                    return false
                }

                // Intercept and ask before background script redirects/popups
                showRedirectDialog(url)
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isAdUrl(url)) {
                    // Block ad resources (images, scripts, etc.)
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", null)
                }
                // Non-browser: if the page fetches an HLS playlist, prefer native PlaybackFragment
                // (ExoPlayer stall recovery) over a stuck HTML5/hls.js player.
                // Skip suppressed (just-failed) stream so returning from player does not loop.
                if (!isBrowserCard && !handedOffToNativePlayer && isHlsPlaylistUrl(url) &&
                    !streamUrlsMatch(url, suppressHandoffUrl)
                ) {
                    activity?.runOnUiThread {
                        if (isAdded && !handedOffToNativePlayer &&
                            !streamUrlsMatch(url, suppressHandoffUrl)
                        ) {
                            handOffToNativePlayer(url)
                        }
                    }
                }
                return null
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                showToast("Failed to load page: $description")
                exitToMain()
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                activity?.runOnUiThread {
                    if (isAdded && context != null) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("SSL Certificate Error")
                            .setMessage("The site's security certificate is not trusted.\n\n${error?.toString()}\n\nProceed anyway (insecure)?")
                            .setPositiveButton("Proceed") { _, _ ->
                                handler?.proceed()
                            }
                            .setNegativeButton("Cancel") { _, _ ->
                                handler?.cancel()
                                if (isBrowserCard) {
                                    exitToMain()
                                }
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        handler?.cancel()
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) {
                    webProgress.visibility = View.GONE
                } else {
                    webProgress.visibility = View.VISIBLE
                    webProgress.progress = newProgress
                }
            }

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
                if (!webInputKeyboardShown) {
                    container.requestFocus()
                }

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
                // Always block new windows / popups (including ad-related ones).
                // This prevents unwanted ad popups and force-opens in the main view if needed.
                // (We already had a prompt-based version; now stricter block for cleaner experience.)
                return true
            }
        }

        webView.loadUrl(url)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.setOnKeyListener { _, keyCode, event ->
            handleKeyEvent(keyCode, event)
        }
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

    private fun goHome() {
        if (isBrowserCard) {
            val home = Utils.getDefaultBrowserPage(requireContext())
            webView.loadUrl(home)
            urlEditText?.setText(home)
            updateNavigationButtons()
        }
    }

    // Suggested missing Browser functions (some implemented, others for future):
    // - Ad blocking: implemented (isAdUrl + shouldInterceptRequest + auto-block in redirects/popups)
    // - Strict popup/new window blocking: implemented (onCreateWindow always blocks)
    // - goHome(): implemented (toolbar Home). Root BACK exits to main (aligned with Close).
    // - Bookmarks: TODO (store in prefs like "browser_bookmarks", add "Add to Bookmarks", dialog to load)
    // - Visited history: TODO (collect in onPageFinished, dialog like CSV recent sources)
    // - Download listener: TODO (add webView.setDownloadListener to handle file downloads)
    // - SSL errors: TODO (override onReceivedSslError to allow or warn)
    // - Find in page: TODO (webView.findAllAsync etc with UI)
    // - Progress bar: TODO (add ProgressBar to toolbar layout, update in onProgressChanged)
    // - Clear cache/cookies for this session
    // - Share current URL (via intent)
    // (End of suggestions)

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

    private fun isHlsPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase()
        // Segment files (.ts) are not playlists — only hand off real playlists
        if (lower.contains(".ts?") || lower.endsWith(".ts")) return false
        return lower.contains(".m3u8") ||
            lower.contains("application/vnd.apple.mpegurl") ||
            lower.contains("application/x-mpegurl")
    }

    /**
     * Open native PlaybackFragment on top of this WebView for direct stream URLs so
     * ExoPlayer stall / live-edge recovery can run.
     *
     * WebView is [FragmentTransaction.hide]den (not replaced) so page history, clicks,
     * and redirects survive. Back / reconnect-fail pop the player and return here —
     * user does not re-do multi-step navigation from main browse.
     */
    private fun handOffToNativePlayer(streamUrl: String) {
        if (handedOffToNativePlayer || !isAdded || isWebViewDestroyed) return
        if (isBrowserCard) return
        // Page may keep polling the same dead playlist after user leaves the player.
        if (streamUrlsMatch(streamUrl, suppressHandoffUrl)) return

        handedOffToNativePlayer = true
        suppressHandoffUrl = streamUrl
        stopHtmlVideoStallMonitor()
        pauseHtmlMedia()
        if (::webView.isInitialized && !isWebViewDestroyed) {
            try {
                webView.onPause()
                webView.keepScreenOn = false
            } catch (_: Exception) {
            }
        }
        android.util.Log.i("WebViewFragment", "Handing off stream to native player: $streamUrl")
        showToast("Opening stream in player…")

        val fragment = PlaybackFragment().apply {
            arguments = Bundle().apply {
                putString("video_url", streamUrl)
            }
        }
        val fm = parentFragmentManager
        try {
            if (fm.isStateSaved) {
                android.util.Log.w("WebViewFragment", "Hand-off skipped: state already saved")
                handedOffToNativePlayer = false
                return
            }
            // Keep this fragment's view + history under the player (hide, don't replace).
            fm.beginTransaction()
                .hide(this)
                .add(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            android.util.Log.e("WebViewFragment", "Hand-off failed", e)
            handedOffToNativePlayer = false
        }
    }

    /** Loose match so query-order / trailing junk differences still suppress re-handoff. */
    private fun streamUrlsMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true
        fun normalize(u: String): String {
            val noHash = u.substringBefore('#')
            return noHash.trimEnd('/')
        }
        return normalize(a) == normalize(b)
    }

    /**
     * Player was popped — allow a *different* stream to hand off again, but keep
     * [suppressHandoffUrl] so the dead source does not auto-reopen.
     */
    private fun onReturnedFromNativePlayer() {
        if (isWebViewDestroyed || !isAdded) return
        handedOffToNativePlayer = false
        if (::webView.isInitialized) {
            try {
                webView.onResume()
                webView.keepScreenOn = true
            } catch (_: Exception) {
            }
        }
        if (!isBrowserCard) {
            // Resume HTML5 only when we did not suppress a native handoff stream on this page.
            // Stall monitor still useful for pages that never handed off.
            startHtmlVideoStallMonitor()
        }
        android.util.Log.i(
            "WebViewFragment",
            "Returned from native player; suppress re-handoff of: $suppressHandoffUrl"
        )
    }

    private fun tryPlayHtmlVideo() {
        if (isWebViewDestroyed || !isAdded) return
        webView.evaluateJavascript(
            """
            (function() {
                var v = document.querySelector('video');
                if (!v) return 'none';
                try { v.muted = false; v.play(); } catch(e) {}
                return 'play';
            })();
            """.trimIndent(),
            null
        )
    }

    private fun startHtmlVideoStallMonitor() {
        videoStallHandler.removeCallbacks(videoStallCheckRunnable)
        lastHtmlVideoTime = -1.0
        htmlVideoStallTicks = 0
        videoStallHandler.postDelayed(videoStallCheckRunnable, htmlVideoStallIntervalMs)
    }

    private fun stopHtmlVideoStallMonitor() {
        videoStallHandler.removeCallbacks(videoStallCheckRunnable)
    }

    private fun checkHtmlVideoStall() {
        if (isWebViewDestroyed || !isAdded || handedOffToNativePlayer) return
        webView.evaluateJavascript(
            """
            (function() {
                var v = document.querySelector('video');
                if (!v) return JSON.stringify({found:false});
                return JSON.stringify({
                    found:true,
                    t: v.currentTime || 0,
                    paused: !!v.paused,
                    ended: !!v.ended,
                    rs: v.readyState || 0,
                    net: v.networkState || 0,
                    err: (v.error && v.error.code) ? v.error.code : 0,
                    seeking: !!v.seeking
                });
            })();
            """.trimIndent()
        ) { raw ->
            if (isWebViewDestroyed || !isAdded || handedOffToNativePlayer) return@evaluateJavascript
            try {
                if (raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
                // evaluateJavascript returns a JSON-encoded string; unwrap to object text
                val jsonStr = when {
                    raw.startsWith("\"") -> org.json.JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                    else -> raw
                }
                val json = JSONObject(jsonStr)
                if (!json.optBoolean("found", false)) {
                    htmlVideoStallTicks = 0
                    return@evaluateJavascript
                }

                val t = json.optDouble("t", 0.0)
                val paused = json.optBoolean("paused", false)
                val ended = json.optBoolean("ended", false)
                val err = json.optInt("err", 0)
                val readyState = json.optInt("rs", 0)
                val seeking = json.optBoolean("seeking", false)

                // User paused intentionally — don't thrash
                if (paused && err == 0 && !ended) {
                    lastHtmlVideoTime = t
                    htmlVideoStallTicks = 0
                    return@evaluateJavascript
                }

                val timeStuck = lastHtmlVideoTime >= 0 && kotlin.math.abs(t - lastHtmlVideoTime) < 0.15
                val waiting = readyState < 2 || seeking
                val bad = err != 0 || ended || (timeStuck && !seeking)

                if (bad || (timeStuck && waiting)) {
                    htmlVideoStallTicks++
                    android.util.Log.d(
                        "WebViewFragment",
                        "HTML video stall tick $htmlVideoStallTicks/$htmlVideoStallThreshold " +
                            "(t=$t err=$err ended=$ended rs=$readyState)"
                    )
                    if (htmlVideoStallTicks >= htmlVideoStallThreshold) {
                        recoverHtmlVideo()
                        htmlVideoStallTicks = 0
                    }
                } else {
                    htmlVideoStallTicks = 0
                }
                lastHtmlVideoTime = t
            } catch (e: Exception) {
                android.util.Log.w("WebViewFragment", "Stall check parse failed: $raw", e)
            }
        }
    }

    private fun recoverHtmlVideo() {
        if (htmlVideoRecoverCount >= maxHtmlVideoRecovers) {
            android.util.Log.w("WebViewFragment", "HTML video recover budget exhausted")
            showToast("Stream stalled. Leaving player…")
            stopHtmlVideoStallMonitor()
            exitToMain()
            return
        }
        htmlVideoRecoverCount++
        showToast("Stream stalled — reconnecting… ($htmlVideoRecoverCount/$maxHtmlVideoRecovers)")
        android.util.Log.i("WebViewFragment", "Recovering HTML video ($htmlVideoRecoverCount/$maxHtmlVideoRecovers)")

        // Soft recover: reload media element first; every other attempt full page reload
        if (htmlVideoRecoverCount % 2 == 1) {
            webView.evaluateJavascript(
                """
                (function() {
                    var v = document.querySelector('video');
                    if (!v) return 'none';
                    try {
                        var src = v.currentSrc || v.src;
                        v.pause();
                        if (src) { v.src = src; }
                        v.load();
                        var p = v.play();
                        if (p && p.catch) p.catch(function(){});
                        return 'reloaded';
                    } catch(e) { return 'err:' + e; }
                })();
                """.trimIndent(),
                null
            )
        } else {
            webView.reload()
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
        // For the URL bar (treated as nav item, not direct editor), do not swallow
        // dpad directions here so the toolbar navigation logic can move between items.
        val isDirectional = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

        if (urlEditText != null && urlEditText!!.isFocused &&
            event.action == KeyEvent.ACTION_DOWN && !isDirectional &&
            keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_DPAD_CENTER) {
            return false
        }

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
                        // Align with stream player: Back exits the current layer (fullscreen).
                        exitFullscreenIfNeeded()
                        return true
                    }
                }
            } else {
                // --- GESTURE MODE (CURSOR IS HIDDEN) ---
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        setCursorVisibility(true)
                        pointerX = (fullscreenContainer.width / 2).toFloat()
                        pointerY = (fullscreenContainer.height / 2).toFloat()
                        updatePointerPosition()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        injectJavaScriptForVideo("video.currentTime -= 10;")
                        showToast("Rewind 10s")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        injectJavaScriptForVideo("video.currentTime += 10;")
                        showToast("Forward 10s")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        handleCenterClick()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        exitFullscreenIfNeeded()
                        return true
                    }
                }
            }
            return true
        }

        // --- Non-Fullscreen Logic ---
        if (!container.hasFocus() && (urlEditText == null || !urlEditText!!.isFocused) && !webInputKeyboardShown) {
            container.requestFocus()
        }

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
                        setCursorVisibility(true)
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
                webInputKeyboardShown = false
                setCursorVisibility(true)
                movePointer(deltaX, deltaY)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleCenterClick()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                handleBackNavigation()
                return true
            }
        }
        return false
    }

    /**
     * Unified back ladder (aligned with native stream "leave current surface"):
     * 1) Exit HTML5/site fullscreen if active
     * 2) Web history if available
     * 3) Return to main browse (same as Close)
     */
    private fun handleBackNavigation() {
        if (isWebViewDestroyed || !isAdded) return
        if (isInFullscreen) {
            exitFullscreenIfNeeded()
            return
        }
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            updateNavigationButtons()
            return
        }
        exitToMain()
    }

    private fun exitFullscreenIfNeeded() {
        if (!isInFullscreen) return
        try {
            if (::webView.isInitialized && !isWebViewDestroyed) {
                webView.webChromeClient?.onHideCustomView()
            } else {
                // Destroy/stop race: clear local FS state if the chrome client is already gone.
                isInFullscreen = false
                if (::fullscreenContainer.isInitialized) {
                    fullscreenContainer.visibility = View.GONE
                    fullscreenContainer.removeAllViews()
                }
            }
        } catch (_: Exception) {
            // Best-effort during power/stop races
            isInFullscreen = false
        }
    }

    /** Leave WebView and return to main browse. Does not quit the app process. */
    private fun exitToMain() {
        if (!isAdded) return
        exitFullscreenIfNeeded()
        stopHtmlVideoStallMonitor()
        pauseHtmlMedia()
        try {
            if (!parentFragmentManager.isStateSaved) {
                parentFragmentManager.popBackStack()
            }
        } catch (_: Exception) {
        }
    }

    private fun pauseHtmlMedia() {
        if (isWebViewDestroyed || !::webView.isInitialized) return
        try {
            webView.evaluateJavascript(
                """
                (function() {
                    var nodes = document.querySelectorAll('video, audio');
                    for (var i = 0; i < nodes.length; i++) {
                        try { nodes[i].pause(); } catch (e) {}
                    }
                })();
                """.trimIndent(),
                null
            )
        } catch (_: Exception) {
        }
    }

    private fun handleCenterClick() {
        clickCount++
        if (clickCount == 1) {
            clickRunnable = Runnable {
                if (isBrowserCard && urlEditText != null && urlEditText!!.isFocused) {
                    showUrlInputDialog(urlEditText!!.text.toString())
                } else if (isInFullscreen) {
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
        webInputKeyboardShown = false
        pointerX += deltaX
        pointerY += deltaY

        val boundsView = if (isInFullscreen) fullscreenContainer else container
        val viewWidth = boundsView.width.toFloat()
        val viewHeight = boundsView.height.toFloat()

        pointerX = pointerX.coerceIn(0f, viewWidth)
        pointerY = pointerY.coerceIn(0f, viewHeight)

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
            } else if (deltaX > 0 && pointerX > container.width - scrollThresholdPx) {
                val maxScrollX = (contentWidth * webView.scaleX - container.width).toInt().coerceAtLeast(0)
                if (webView.scrollX < maxScrollX) webView.scrollBy(pointerSpeed.toInt(), 0)
            }

            // Vertical edge scroll for page content.
            // Do NOT guard with isPointerInToolbar: this allows scrolling up the page
            // even after the toolbar auto-shows when the pointer reaches the top.
            var scrollAmount = 0
            if (pointerY < scrollThresholdPx) {
                scrollAmount = -pointerSpeed.toInt()
            } else if (pointerY > container.height - scrollThresholdPx) {
                scrollAmount = pointerSpeed.toInt()
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
        pointer.x = pointerX - pointer.width / 2f
        pointer.y = pointerY - pointer.height / 2f
    }

    private fun toggleFullscreen() {
        val js = """
            (function() {
                var target = document.querySelector('video');
                if (!target) {
                    var iframes = document.querySelectorAll('iframe');
                    var maxArea = 0;
                    for (var i = 0; i < iframes.length; i++) {
                        var rect = iframes[i].getBoundingClientRect();
                        var area = rect.width * rect.height;
                        if (area > maxArea) {
                            maxArea = area;
                            target = iframes[i];
                        }
                    }
                }
                if (target) {
                    if (document.fullscreenElement || document.webkitFullscreenElement) {
                        document.exitFullscreen ? document.exitFullscreen() : document.webkitExitFullscreen();
                    } else {
                        target.requestFullscreen ? target.requestFullscreen() : target.webkitRequestFullscreen();
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
    private fun getToolbarNavItems(): List<View> {
        val base = listOf(
            btnWebBack, btnWebForward, btnWebRefresh, btnWebHome,
            btnWebAutoFullscreen, btnWebDesktopToggle, btnWebAdblockToggle, btnWebClose
        )
        return if (isBrowserCard && urlEditText != null) {
            // Insert URL bar after Adblock (visual order: ... home, auto, desktop, adblock, url, close)
            base.take(7) + listOf(urlEditText!!) + base.takeLast(1)
        } else {
            base
        }.filter { it.visibility == View.VISIBLE }
    }

    private fun navigateToolbarButtons(isRight: Boolean) {
        if (isWebViewDestroyed || !isAdded || view == null) return

        val items = getToolbarNavItems()
        if (items.isEmpty()) return

        var currentFocusedIndex = items.indexOfFirst { it.isFocused }
        if (currentFocusedIndex == -1) {
            val itemLocation = IntArray(2)
            var minDistance = Float.MAX_VALUE
            val pointerCenterX = pointerX
            for (i in items.indices) {
                val item = items[i]
                item.getLocationInWindow(itemLocation)
                val itemLeft = itemLocation[0]
                val itemWidth = item.width
                val itemCenterX = itemLeft + itemWidth / 2f
                val dist = Math.abs(pointerCenterX - itemCenterX)
                if (dist < minDistance) {
                    minDistance = dist
                    currentFocusedIndex = i
                }
            }
        }

        val targetIndex = if (isRight) {
            (currentFocusedIndex + 1).coerceAtMost(items.size - 1)
        } else {
            (currentFocusedIndex - 1).coerceAtLeast(0)
        }

        val targetItem = items[targetIndex]
        val itemLocation = IntArray(2)
        targetItem.getLocationInWindow(itemLocation)
        val itemLeft = itemLocation[0]
        val itemWidth = targetItem.width
        val itemTop = itemLocation[1]
        val itemHeight = targetItem.height

        val boundsView = if (isInFullscreen) fullscreenContainer else container

        // Target center coordinates relative to window
        val targetCenterX = itemLeft + itemWidth / 2f
        val targetCenterY = itemTop + itemHeight / 2f

        // Convert window coordinate to local container coordinate
        val containerLocation = IntArray(2)
        boundsView.getLocationInWindow(containerLocation)
        val localX = targetCenterX - containerLocation[0]
        val localY = targetCenterY - containerLocation[1]

        pointerX = localX
        pointerY = localY

        pointerX = pointerX.coerceIn(0f, boundsView.width.toFloat())
        pointerY = pointerY.coerceIn(0f, boundsView.height.toFloat())

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
            val items = getToolbarNavItems()
            if (items.isEmpty()) return

            var nearestItem: View? = null
            var minDistance = Float.MAX_VALUE
            val pointerCenterX = pointerX
            val itemLocation = IntArray(2)

            for (item in items) {
                if (item.visibility != View.VISIBLE) continue
                item.getLocationInWindow(itemLocation)
                val itemLeft = itemLocation[0]
                val itemWidth = item.width
                val itemCenterX = itemLeft + itemWidth / 2f

                val distance = Math.abs(pointerCenterX - itemCenterX)
                if (distance < minDistance) {
                    minDistance = distance
                    nearestItem = item
                }
            }

            for (item in items) {
                if (item == nearestItem) {
                    if (!item.isFocused) {
                        item.requestFocus()
                    }
                } else {
                    if (item.isFocused) {
                        item.clearFocus()
                    }
                }
            }
        } else {
            pointer.visibility = View.VISIBLE
            isCursorShowing = true
            if (!container.hasFocus() && !webInputKeyboardShown) {
                container.requestFocus()
            }
            // Clear focus from all toolbar items (general, includes adblock and url for browser)
            for (item in getToolbarNavItems()) {
                if (item.isFocused) {
                    item.clearFocus()
                }
            }
        }
    }

    private fun setCursorVisibility(show: Boolean) {
        if (!::pointer.isInitialized || !::pointerContainer.isInitialized) {
            // Too early (e.g. during onViewCreated before setupPointer). Skip safely.
            return
        }
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
        downEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        upEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN

        if (isInFullscreen) {
            fullscreenContainer.dispatchTouchEvent(downEvent)
            fullscreenContainer.dispatchTouchEvent(upEvent)
        } else {
            container.dispatchTouchEvent(downEvent)
            container.dispatchTouchEvent(upEvent)

            // Synthetic touches don't always trigger standard clicks in WebViews on newer OS versions.
            // Execute a robust JavaScript click as a fallback.
            val density = resources.displayMetrics.density
            val cssX = (pointerX / density).toInt()
            val cssY = (pointerY / density).toInt()
            webView.evaluateJavascript("""
                (function() {
                    var el = document.elementFromPoint($cssX, $cssY);
                    if (el) {
                        el.click();
                        el.focus();
                    }
                })();
            """.trimIndent(), null)

            // Try to show soft keyboard if an input field received focus.
            webView.evaluateJavascript(
                "document.activeElement && document.activeElement.focus && document.activeElement.focus()",
                null
            )
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isWebViewDestroyed && isAdded) {
                    webView.evaluateJavascript(
                        "(function(){var el=document.activeElement;return !!(el && (el.tagName==='INPUT' || el.tagName==='TEXTAREA' || el.isContentEditable));})()",
                        { value ->
                            if (value != null && "true".equals(value.trim())) {
                                // Allow focus temporarily for IME
                                webView.isFocusable = true
                                webView.isFocusableInTouchMode = true
                                webView.requestFocus()
                                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
                                webInputKeyboardShown = true
                            }
                        }
                    )
                }
            }, 150)
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
        // Soft pause (same path as Power/Home intermediate step).
        if (::webView.isInitialized && !isWebViewDestroyed) {
            pauseHtmlMedia()
            webView.onPause()
            webView.keepScreenOn = false
        }
    }

    /**
     * Align with PlaybackFragment power/Home handling: leave fullscreen, stop stall
     * recovery, and pause media so nothing keeps running in the background.
     */
    override fun onStop() {
        super.onStop()
        exitFullscreenIfNeeded()
        stopHtmlVideoStallMonitor()
        pauseHtmlMedia()
        if (::webView.isInitialized && !isWebViewDestroyed) {
            try {
                webView.onPause()
                webView.keepScreenOn = false
            } catch (_: Exception) {
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // Shown again after PlaybackFragment pop (hide/add handoff).
            onReturnedFromNativePlayer()
        } else {
            stopHtmlVideoStallMonitor()
            pauseHtmlMedia()
        }
    }

    override fun onStart() {
        super.onStart()
        // Hidden under native player — do not resume WebView media/stall recovery.
        if (isWebViewDestroyed || !::webView.isInitialized || !isAdded || isHidden) return
        try {
            webView.onResume()
        } catch (_: Exception) {
        }
        // Non-browser stream pages: resume HTML5 playback + stall recovery after power/Home.
        if (!isBrowserCard) {
            tryPlayHtmlVideo()
            startHtmlVideoStallMonitor()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isHidden) return
        if (::webView.isInitialized && !isWebViewDestroyed) {
            webView.onResume()
        }
        if (!webInputKeyboardShown && ::container.isInitialized) {
            container.requestFocus()
        }
        updateContentDimensions()
    }

    override fun onDestroyView() {
        // Tear down FS + monitors before marking destroyed so hide callbacks can run.
        stopHtmlVideoStallMonitor()
        exitFullscreenIfNeeded()
        isWebViewDestroyed = true
        super.onDestroyView()
        pointerHideHandler.removeCallbacksAndMessages(null)
        keyResetHandler.removeCallbacksAndMessages(null)
        jsHandler.removeCallbacksAndMessages(null)
        clickHandler.removeCallbacksAndMessages(null)
        videoStallHandler.removeCallbacksAndMessages(null)

        try {
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
        } catch (e: Exception) {
            // Ignore errors during shutdown to avoid DeadObject or other during window exit
        }
    }
}