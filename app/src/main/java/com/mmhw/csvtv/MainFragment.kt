package com.mmhw.csvtv

import android.content.Intent
import android.content.Context
import android.content.IntentFilter
import android.app.DownloadManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainFragment : BrowseSupportFragment() {
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var videos: List<Video> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private var latestUpdateVersion: String? = null
    private var latestUpdateApkUrl: String? = null
    private var updateDownloadId: Long = -1L
    private var isUpdateDownloaded = false
    private var isUpdateDownloadInProgress = false

    private var isActive = true

    private val onDownloadCompleteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == updateDownloadId && id != -1L) {
                context?.let { ctx ->
                    val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor = downloadManager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            isUpdateDownloaded = true
                            isUpdateDownloadInProgress = false
                            Log.d("MainFragment", "Update download completed successfully.")
                            latestUpdateVersion?.let { version ->
                                ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                                    .edit().putString("downloaded_update_version", version).apply()
                            }
                            // Auto-launch the system installer (completes the "click card to update" flow)
                            installApk(ctx)
                        } else {
                            isUpdateDownloadInProgress = false
                            val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIdx != -1) cursor.getInt(reasonIdx) else -1
                            Log.e("MainFragment", "Download failed. Status: $status, Reason: $reason")
                            Toast.makeText(ctx, "Update download failed.", Toast.LENGTH_SHORT).show()
                        }
                        cursor.close()
                    }
                }
            }
        }
    }
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateDateTime()
            handler.postDelayed(this, 60_000)
        }
    }
    private var focusedVideo: Video? = null
    private val focusedVideoRefreshHandler = Handler(Looper.getMainLooper())
    private var isFirstRefreshOfCurrentFocused = true
    private val focusedVideoRefreshRunnable = object : Runnable {
        override fun run() {
            val video = focusedVideo
            if (video != null) {
                refreshFocusedVideoMetadata(video)
                val nextDelay = if (isFirstRefreshOfCurrentFocused) {
                    isFirstRefreshOfCurrentFocused = false
                    60_000L
                } else {
                    60_000L
                }
                focusedVideoRefreshHandler.postDelayed(this, nextDelay)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        loadData()
        
        try {
            requireContext().registerReceiver(
                onDownloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        } catch (e: Exception) {
            Log.e("MainFragment", "Failed to register download receiver", e)
        }
        
        checkForUpdates()
    }

    private fun setupUI() {
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        adapter = rowsAdapter

        updateDateTime()
        handler.post(updateTimeRunnable)

        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, row ->
            when (item) {
                is Video -> handleVideoClick(item)
                is HeaderItem -> handleHeaderClick(item, row)
            }
            false
        })

        setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ ->
            focusedVideoRefreshHandler.removeCallbacks(focusedVideoRefreshRunnable)
            val video = item as? Video
            val urlLower = video?.url?.trim()?.lowercase() ?: ""
            if (video != null && urlLower != "settings" && urlLower != "refresh" && urlLower != "browser") {
                focusedVideo = video
                isFirstRefreshOfCurrentFocused = true
                focusedVideoRefreshHandler.postDelayed(focusedVideoRefreshRunnable, 10_000L)
            } else {
                focusedVideo = null
            }
        })
    }

    private fun handleVideoClick(video: Video) {
        val url = video.url?.trim() ?: ""
        val title = video.title?.trim() ?: ""
        Log.d("MainFragment", "Video clicked: title=$title, url=$url")

        when {
            url.equals("browser", ignoreCase = true) -> {
                val browserUrl = Utils.getSearchEngineUrl(requireContext())
                openWebViewFragment(browserUrl, isBrowserCard = true)
                return
            }
            url.equals("update", ignoreCase = true) || title.startsWith("Update available", ignoreCase = true) -> {
                val context = requireContext()
                if (isUpdateDownloaded) {
                    installApk(context)
                } else if (isUpdateDownloadInProgress) {
                    Toast.makeText(context, "Update download is already in progress.", Toast.LENGTH_SHORT).show()
                } else {
                    latestUpdateApkUrl?.let { apkUrl ->
                        startUpdateDownload(context, apkUrl)
                    } ?: run {
                        Toast.makeText(context, "Update URL not found", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
            url.equals("refresh", ignoreCase = true) || title.equals("Refresh", ignoreCase = true) -> {
                Toast.makeText(requireContext(), "Refreshing video list...", Toast.LENGTH_SHORT).show()
                loadData()
                return
            }
            url.equals("settings", ignoreCase = true) || title.equals("Settings", ignoreCase = true) -> {
                // Pop all fragments from the back stack to destroy any open WebView or PlaybackFragment.
                parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

                // Start the Setup activity.
                val intent = Intent(requireContext(), SetupActivity::class.java)
                startActivity(intent)

                // Finish the current MainActivity to ensure a clean start after setup is complete.
                requireActivity().finish()
                return
            }
            url.startsWith("rtmp://") -> {
                Log.d("MainFragment", "Opening PlaybackFragment directly for RTMP URL: $url")
                Utils.incrementWatchCount(requireContext(), url)
                openPlaybackFragment(url)
                return
            }
            else -> {
                Toast.makeText(requireContext(), "Resolving URL...", Toast.LENGTH_SHORT).show()

                Utils.resolveUrl(url, requireContext()) { resolvedUrl, contentType, format, resolution, error, isAudioOnly, audioChannels ->
                    if (!resolvedUrl.isNullOrBlank() && error == null) {
                        Utils.incrementWatchCount(requireContext(), url)
                        Log.d("MainFragment", "Resolved URL: $url -> $resolvedUrl, Content-Type: $contentType, isVideoStream=${Utils.isVideoStream(resolvedUrl, contentType)}")
                        if (Utils.isVideoStream(resolvedUrl, contentType)) {
                            Log.d("MainFragment", "Opening PlaybackFragment for resolved URL: $resolvedUrl, Content-Type: $contentType")
                            openPlaybackFragment(resolvedUrl, contentType)
                        } else {
                            Log.d("MainFragment", "Opening WebViewFragment for resolved URL: $resolvedUrl")
                            openWebViewFragment(resolvedUrl)
                        }
                    } else {
                        Log.w("MainFragment", "Failed to resolve URL: $url, error: $error")
                        Log.d("MainFragment", "Opening WebViewFragment for URL: $url")
                        openWebViewFragment(url)
                    }
                }
            }
        }
    }

    private fun handleHeaderClick(header: HeaderItem, row: Any) {
        if (header.name != "Settings") {
            val rowIndex = rowsAdapter.indexOf(row)
            setSelectedPosition(rowIndex, true)
        }
    }

    private fun openPlaybackFragment(url: String, mimeType: String? = null) {
        val fragment = PlaybackFragment().apply {
            arguments = Bundle().apply {
                putString("video_url", url)
                putString("mime_type", mimeType)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openWebViewFragment(url: String, isBrowserCard: Boolean = false) {
        val fragment = WebViewFragment().apply {
            arguments = Bundle().apply {
                putString("url", url)
                if (isBrowserCard) {
                    putBoolean("is_browser_card", true)
                }
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun updateDateTime() {
        title = SimpleDateFormat("EEEE, MMM d, HH:mm", Locale.getDefault()).format(Date())
    }

    private fun loadData() {
        val sharedPrefs = requireContext().getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE)
        val sheetLink = sharedPrefs.getString("sheet_link", null)

        if (sheetLink.isNullOrBlank()) {
            processVideosData(null, "No CSV source configured")
            return
        }

        Utils.fetchSheetData(requireContext(), sheetLink) { videos, error ->
            processVideosData(videos, error)
        }
    }

    private fun processVideosData(videos: List<Video>?, error: String?) {
        // Callbacks from OkHttp come on background threads. All UI work (rows, title)
        // must be posted to the main thread.
        activity?.runOnUiThread {
            if (!isAdded || isDetached) return@runOnUiThread

            if (videos.isNullOrEmpty() || error != null) {
                showSettingsAndRefreshOnly()
            } else {
                this.videos = videos
                context?.let { ctx ->
                    for (video in videos) {
                        val cached = Utils.getPersistentResolvedMetadata(ctx, video.url)
                        if (cached != null) {
                            video.isValid = true
                            video.videoFormat = cached.format
                            video.resolution = cached.resolution
                            video.isAudioOnly = cached.isAudioOnly
                            video.audioChannels = cached.audioChannels
                        }
                    }
                }
                updateRows()
                startVideoPrecheck()
            }

            updateDateTime()
        }
    }

    private fun startVideoPrecheck() {
        if (!isActive || !isAdded || isDetached) return
        
        // Gather all unique videos (by url) to check, excluding refresh/settings
        val uniqueVideos = videos.filter {
            val urlLower = it.url.trim().lowercase()
            urlLower != "settings" && urlLower != "refresh" && urlLower != "browser"
        }.distinctBy { it.url }

        // Sort by watch count descending
        val checkQueue = uniqueVideos.sortedByDescending {
            Utils.getWatchCount(requireContext(), it.url)
        }.toMutableList()

        checkNextVideo(checkQueue)
    }

    private fun checkNextVideo(queue: MutableList<Video>) {
        if (!isActive || queue.isEmpty() || !isAdded || isDetached) {
            queue.clear()
            return
        }

        val video = queue.removeAt(0)
        video.isChecking = true
        updateVideoCard(video)

        val startTime = System.currentTimeMillis()
        val context = context ?: return

        Utils.resolveUrl(video.url, context) { resolvedUrl, contentType, format, resolution, error, isAudioOnly, audioChannels ->
            val ping = System.currentTimeMillis() - startTime
            
            activity?.runOnUiThread {
                if (!isActive || !isAdded || isDetached) {
                    queue.clear()
                    return@runOnUiThread
                }
                
                video.isChecking = false
                video.pingMs = ping
                
                if (resolvedUrl != null && error == null) {
                    video.isValid = true
                    video.videoFormat = format
                    video.resolution = resolution
                    video.isAudioOnly = isAudioOnly
                    video.audioChannels = audioChannels
                } else {
                    video.isValid = false
                }
                
                updateVideoCard(video)
                
                // Proceed to next video after a 500ms delay to keep network light
                handler.postDelayed({
                    if (isActive) checkNextVideo(queue)
                }, 500)
            }
        }
    }

    private fun updateVideoCard(video: Video) {
        Log.d("MainFragment", "updateVideoCard: title=${video.title}, isValid=${video.isValid}, ping=${video.pingMs}ms")
        for (i in 0 until rowsAdapter.size()) {
            val row = rowsAdapter.get(i) as? ListRow ?: continue
            val rowAdapter = row.adapter as? ArrayObjectAdapter ?: continue
            for (j in 0 until rowAdapter.size()) {
                val item = rowAdapter.get(j) as? Video ?: continue
                if (item.url == video.url) {
                    rowAdapter.replace(j, video)
                    rowAdapter.notifyArrayItemRangeChanged(j, 1)
                    Log.d("MainFragment", "Notified adapter for video: ${video.title} at index $j in row $i")
                }
            }
        }
    }

    private fun refreshFocusedVideoMetadata(video: Video) {
        if (!isActive || !isAdded || isDetached) return
        val startTime = System.currentTimeMillis()
        val context = context ?: return

        Utils.resolveUrl(video.url, context, bypassCache = true) { resolvedUrl, contentType, format, resolution, error, isAudioOnly, audioChannels ->
            val ping = System.currentTimeMillis() - startTime
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                video.pingMs = ping
                if (resolvedUrl != null && error == null) {
                    video.isValid = true
                    video.videoFormat = format
                    video.resolution = resolution
                    video.isAudioOnly = isAudioOnly
                    video.audioChannels = audioChannels
                } else {
                    video.isValid = false
                }
                updateVideoCard(video)
            }
        }
    }

    private fun showSettingsAndRefreshOnly() {
        rowsAdapter.clear()
        val settingsHeader = HeaderItem(0, "Settings")
        val settingsRowAdapter = ArrayObjectAdapter(CardPresenter(this)).apply {
            add(Video("Browser", "browser", null))
            add(Video("Refresh", "Refresh", null))
            add(Video("Settings", "Settings", null))
        }
        rowsAdapter.add(ListRow(settingsHeader, settingsRowAdapter))
    }

    private fun updateRows() {
        rowsAdapter.clear()

        val groupNames = videos.map { it.groupName }.distinct().sorted()
        val drawerItems = groupNames + listOf("Settings")

        drawerItems.forEachIndexed { index, item ->
            val header = HeaderItem(index.toLong(), item)
            val rowAdapter = if (item == "Settings") {
                ArrayObjectAdapter(CardPresenter(this)).apply {
                    latestUpdateVersion?.let { version ->
                        add(Video("Update available v$version", "update", null))
                    }
                    add(Video("Browser", "browser", null))
                    add(Video("Refresh", "Refresh", null))
                    add(Video("Settings", "Settings", null))
                }
            } else {
                ArrayObjectAdapter(CardPresenter(this)).apply {
                    val filteredVideos = videos.filter { it.groupName == item }
                    if (filteredVideos.isEmpty()) {
                        add(Video("", "No videos available for this group.", null))
                    } else {
                        addAll(0, filteredVideos)
                    }
                }
            }

            rowsAdapter.add(ListRow(header, rowAdapter))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
        handler.removeCallbacksAndMessages(null)
        focusedVideoRefreshHandler.removeCallbacksAndMessages(null)
        try {
            requireContext().unregisterReceiver(onDownloadCompleteReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    private fun checkForUpdates() {
        val context = context ?: return
        Log.d("MainFragment", "Checking for updates...")
        Utils.checkAppUpdate(context) { newVersion, apkUrl, error ->
            Log.d("MainFragment", "Update check result: newVersion=$newVersion, apkUrl=$apkUrl, error=$error (currentVersion from package: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName})")
            if (error != null) {
                Log.e("MainFragment", "Update check failed: $error")
                return@checkAppUpdate
            }

            if (newVersion != null && apkUrl != null) {
                Log.d("MainFragment", "New update available: v$newVersion")
                latestUpdateVersion = newVersion
                latestUpdateApkUrl = apkUrl

                activity?.runOnUiThread {
                    if (!isActive || !isAdded || isDetached) return@runOnUiThread

                    // Verify if it is already downloaded
                    val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    val downloadedVersion = prefs.getString("downloaded_update_version", null)
                    val localFile = java.io.File(context.externalCacheDir ?: context.cacheDir, "update.apk")
                    
                    if (downloadedVersion == newVersion && localFile.exists()) {
                        isUpdateDownloaded = true
                    }

                    // Update Settings Row to show the update card (placed before Browser card)
                    updateRows()
                }
            }
        }
    }

    private fun startUpdateDownload(context: Context, apkUrl: String) {
        if (isUpdateDownloadInProgress) {
            Toast.makeText(context, "Update download is already in progress.", Toast.LENGTH_SHORT).show()
            return
        }
        isUpdateDownloadInProgress = true

        val localFile = java.io.File(context.externalCacheDir ?: context.cacheDir, "update.apk")
        if (localFile.exists()) {
            localFile.delete()
        }

        Toast.makeText(context, "Downloading update... Installer will launch automatically when done.", Toast.LENGTH_LONG).show()

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("CSV TV Update")
            .setDescription("Downloading version $latestUpdateVersion")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(localFile))

        updateDownloadId = downloadManager.enqueue(request)
    }

    private fun installApk(context: Context) {
        val localFile = java.io.File(context.externalCacheDir ?: context.cacheDir, "update.apk")
        if (!localFile.exists()) {
            Toast.makeText(context, "APK not found. Please download again.", Toast.LENGTH_SHORT).show()
            return
        }

        val apkUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            localFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e("MainFragment", "Failed to start install activity", e)
            Toast.makeText(context, "Failed to start installation: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}