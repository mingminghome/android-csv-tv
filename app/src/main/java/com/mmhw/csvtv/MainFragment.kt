package com.mmhw.csvtv

import android.app.Dialog
import android.content.Intent
import android.content.Context
import android.content.IntentFilter
import android.app.DownloadManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
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
    private var latestUpdateTitle: String? = null
    private var latestUpdateNotes: String? = null
    private var updateDownloadId: Long = -1L
    private var isUpdateDownloaded = false
    private var isUpdateDownloadInProgress = false

    private var updateDialog: Dialog? = null
    private var updateActionBtn: Button? = null
    private var updateCancelBtn: Button? = null
    private var updateProgressContainer: View? = null
    private var updateProgressBar: ProgressBar? = null
    private var updateProgressLabel: TextView? = null
    private var updateProgressPercent: TextView? = null

    private var isActive = true

    private val downloadProgressRunnable = object : Runnable {
        override fun run() {
            if (!isUpdateDownloadInProgress || updateDownloadId == -1L) return
            val ctx = context ?: return
            queryAndUpdateDownloadProgress(ctx)
            if (isUpdateDownloadInProgress) {
                handler.postDelayed(this, 400L)
            }
        }
    }

    private val onDownloadCompleteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == updateDownloadId && id != -1L) {
                context?.let { ctx ->
                    handler.removeCallbacks(downloadProgressRunnable)
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
                            refreshUpdateDialogUi()
                            Toast.makeText(ctx, "Download complete. Tap Install to continue.", Toast.LENGTH_LONG).show()
                        } else {
                            isUpdateDownloadInProgress = false
                            val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIdx != -1) cursor.getInt(reasonIdx) else -1
                            Log.e("MainFragment", "Download failed. Status: $status, Reason: $reason")
                            Toast.makeText(ctx, "Update download failed.", Toast.LENGTH_SHORT).show()
                            refreshUpdateDialogUi()
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
                showUpdateDialog()
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
                    val finalUrlToOpen = if (!resolvedUrl.isNullOrBlank()) resolvedUrl else url
                    // Prefer native player for real streams and IPTV gateways so stall recovery works.
                    // WebView is a last resort for generic web pages (no auto-reconnect for live video).
                    val preferNativePlayer =
                        Utils.isVideoStream(finalUrlToOpen, contentType) ||
                            Utils.looksLikeIptvStreamUrl(finalUrlToOpen) ||
                            format == "M3U8" || format == "MP4" || format == "TS" || format == "RTMP" ||
                            contentType?.contains("mpegurl", ignoreCase = true) == true

                    if (!resolvedUrl.isNullOrBlank() && error == null) {
                        Utils.incrementWatchCount(requireContext(), url)
                        Log.d(
                            "MainFragment",
                            "Resolved URL: $url -> $resolvedUrl, Content-Type: $contentType, " +
                                "format=$format, isVideoStream=${Utils.isVideoStream(resolvedUrl, contentType)}, " +
                                "preferNative=$preferNativePlayer"
                        )
                        if (preferNativePlayer) {
                            Log.d("MainFragment", "Opening PlaybackFragment for resolved URL: $resolvedUrl, Content-Type: $contentType")
                            openPlaybackFragment(resolvedUrl, contentType)
                        } else {
                            Log.d("MainFragment", "Opening WebViewFragment for resolved URL: $resolvedUrl")
                            openWebViewFragment(resolvedUrl)
                        }
                    } else {
                        Log.w("MainFragment", "Failed to resolve URL: $url, error: $error, preferNative=$preferNativePlayer")
                        Utils.incrementWatchCount(requireContext(), url)
                        if (preferNativePlayer) {
                            Log.d("MainFragment", "Opening PlaybackFragment for URL despite error: $finalUrlToOpen")
                            openPlaybackFragment(finalUrlToOpen, contentType)
                        } else {
                            Log.d("MainFragment", "Opening WebViewFragment for URL: $finalUrlToOpen")
                            openWebViewFragment(finalUrlToOpen)
                        }
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
        dismissUpdateDialog()
        try {
            requireContext().unregisterReceiver(onDownloadCompleteReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    private fun checkForUpdates() {
        val context = context ?: return
        Log.d("MainFragment", "Checking for updates...")
        Utils.checkAppUpdate(context) { newVersion, apkUrl, releaseTitle, releaseNotes, error ->
            Log.d(
                "MainFragment",
                "Update check result: newVersion=$newVersion, apkUrl=$apkUrl, error=$error " +
                    "(currentVersion from package: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName})"
            )
            if (error != null) {
                Log.e("MainFragment", "Update check failed: $error")
                return@checkAppUpdate
            }

            if (newVersion != null && apkUrl != null) {
                Log.d("MainFragment", "New update available: v$newVersion")
                latestUpdateVersion = newVersion
                latestUpdateApkUrl = apkUrl
                latestUpdateTitle = releaseTitle
                latestUpdateNotes = releaseNotes

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

    private fun showUpdateDialog() {
        val activity = activity ?: return
        val version = latestUpdateVersion
        if (version == null || latestUpdateApkUrl == null) {
            Toast.makeText(activity, "Update details not available yet.", Toast.LENGTH_SHORT).show()
            return
        }

        if (updateDialog?.isShowing == true) {
            refreshUpdateDialogUi()
            return
        }

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_update)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.62f).toInt().coerceIn(520, 900),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val titleView = dialog.findViewById<TextView>(R.id.update_dialog_title)
        val subtitleView = dialog.findViewById<TextView>(R.id.update_dialog_subtitle)
        val notesView = dialog.findViewById<TextView>(R.id.update_dialog_notes)
        updateProgressContainer = dialog.findViewById(R.id.update_progress_container)
        updateProgressBar = dialog.findViewById(R.id.update_progress_bar)
        updateProgressLabel = dialog.findViewById(R.id.update_progress_label)
        updateProgressPercent = dialog.findViewById(R.id.update_progress_percent)
        updateActionBtn = dialog.findViewById(R.id.update_btn_action)
        updateCancelBtn = dialog.findViewById(R.id.update_btn_cancel)

        titleView.text = latestUpdateTitle?.takeIf { it.isNotBlank() } ?: "Update available"
        subtitleView.text = "v$version  →  current v${currentAppVersionName()}"
        notesView.text = Utils.formatReleaseNotesForDisplay(latestUpdateNotes)

        updateActionBtn?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            when {
                isUpdateDownloaded -> {
                    installApk(ctx)
                }
                isUpdateDownloadInProgress -> {
                    // no-op while downloading
                }
                else -> {
                    latestUpdateApkUrl?.let { startUpdateDownload(ctx, it) }
                        ?: Toast.makeText(ctx, "Update URL not found", Toast.LENGTH_SHORT).show()
                }
            }
        }

        updateCancelBtn?.setOnClickListener {
            if (isUpdateDownloadInProgress) {
                cancelUpdateDownload()
            } else {
                dismissUpdateDialog()
            }
        }

        dialog.setOnDismissListener {
            // Keep download running in background; only clear UI refs.
            if (updateDialog === dialog) {
                clearUpdateDialogRefs()
            }
        }

        updateDialog = dialog
        refreshUpdateDialogUi()
        if (isUpdateDownloadInProgress) {
            handler.removeCallbacks(downloadProgressRunnable)
            handler.post(downloadProgressRunnable)
        }
        dialog.show()
        // Prefer primary action for D-pad focus on TV
        updateActionBtn?.requestFocus()
    }

    private fun refreshUpdateDialogUi() {
        if (updateDialog?.isShowing != true) return

        when {
            isUpdateDownloaded -> {
                updateProgressContainer?.visibility = View.VISIBLE
                updateProgressBar?.isIndeterminate = false
                updateProgressBar?.progress = 100
                updateProgressLabel?.text = "Download complete"
                updateProgressPercent?.text = "100%"
                updateActionBtn?.isEnabled = true
                updateActionBtn?.text = "Install"
                updateCancelBtn?.text = "Close"
            }
            isUpdateDownloadInProgress -> {
                updateProgressContainer?.visibility = View.VISIBLE
                updateActionBtn?.isEnabled = false
                updateActionBtn?.text = "Downloading…"
                updateCancelBtn?.text = "Cancel download"
                if (updateProgressBar?.progress == 0) {
                    updateProgressLabel?.text = "Starting download…"
                    updateProgressPercent?.text = "0%"
                }
            }
            else -> {
                updateProgressContainer?.visibility = View.GONE
                updateProgressBar?.progress = 0
                updateProgressPercent?.text = "0%"
                updateActionBtn?.isEnabled = true
                updateActionBtn?.text = "Download"
                updateCancelBtn?.text = "Cancel"
            }
        }
    }

    private fun startUpdateDownload(context: Context, apkUrl: String) {
        if (isUpdateDownloadInProgress) {
            Toast.makeText(context, "Update download is already in progress.", Toast.LENGTH_SHORT).show()
            return
        }
        isUpdateDownloadInProgress = true
        isUpdateDownloaded = false

        val localFile = java.io.File(context.externalCacheDir ?: context.cacheDir, "update.apk")
        if (localFile.exists()) {
            localFile.delete()
        }
        context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .edit().remove("downloaded_update_version").apply()

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("CSV TV Update")
            .setDescription("Downloading version $latestUpdateVersion")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(localFile))

        updateDownloadId = downloadManager.enqueue(request)
        refreshUpdateDialogUi()
        handler.removeCallbacks(downloadProgressRunnable)
        handler.post(downloadProgressRunnable)
    }

    private fun cancelUpdateDownload() {
        val ctx = context ?: return
        if (updateDownloadId != -1L) {
            try {
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(updateDownloadId)
            } catch (e: Exception) {
                Log.w("MainFragment", "Failed to cancel download", e)
            }
        }
        handler.removeCallbacks(downloadProgressRunnable)
        updateDownloadId = -1L
        isUpdateDownloadInProgress = false
        isUpdateDownloaded = false

        val localFile = java.io.File(ctx.externalCacheDir ?: ctx.cacheDir, "update.apk")
        if (localFile.exists()) localFile.delete()
        ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .edit().remove("downloaded_update_version").apply()

        Toast.makeText(ctx, "Download cancelled.", Toast.LENGTH_SHORT).show()
        refreshUpdateDialogUi()
    }

    private fun queryAndUpdateDownloadProgress(context: Context) {
        if (updateDownloadId == -1L) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(updateDownloadId)) ?: return
        try {
            if (!cursor.moveToFirst()) return
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1
            val downloaded = if (bytesIdx != -1) cursor.getLong(bytesIdx) else 0L
            val total = if (totalIdx != -1) cursor.getLong(totalIdx) else -1L

            when (status) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    updateProgressContainer?.visibility = View.VISIBLE
                    if (total > 0L) {
                        val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
                        updateProgressBar?.isIndeterminate = false
                        updateProgressBar?.progress = percent
                        updateProgressPercent?.text = "$percent%"
                        updateProgressLabel?.text = "Downloading… ${formatBytes(downloaded)} / ${formatBytes(total)}"
                    } else {
                        updateProgressBar?.isIndeterminate = true
                        updateProgressPercent?.text = "…"
                        updateProgressLabel?.text = "Downloading… ${formatBytes(downloaded)}"
                    }
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // Receiver also handles this; keep UI in sync if it fires first.
                    isUpdateDownloaded = true
                    isUpdateDownloadInProgress = false
                    handler.removeCallbacks(downloadProgressRunnable)
                    refreshUpdateDialogUi()
                }
                DownloadManager.STATUS_FAILED -> {
                    isUpdateDownloadInProgress = false
                    handler.removeCallbacks(downloadProgressRunnable)
                    Toast.makeText(context, "Update download failed.", Toast.LENGTH_SHORT).show()
                    refreshUpdateDialogUi()
                }
                DownloadManager.STATUS_PAUSED -> {
                    updateProgressLabel?.text = "Download paused…"
                }
            }
        } finally {
            cursor.close()
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun currentAppVersionName(): String {
        return try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    private fun dismissUpdateDialog() {
        try {
            updateDialog?.dismiss()
        } catch (_: Exception) {
        }
        clearUpdateDialogRefs()
    }

    private fun clearUpdateDialogRefs() {
        updateDialog = null
        updateActionBtn = null
        updateCancelBtn = null
        updateProgressContainer = null
        updateProgressBar = null
        updateProgressLabel = null
        updateProgressPercent = null
    }

    private fun installApk(context: Context) {
        val localFile = java.io.File(context.externalCacheDir ?: context.cacheDir, "update.apk")
        if (!localFile.exists()) {
            Toast.makeText(context, "APK not found. Please download again.", Toast.LENGTH_SHORT).show()
            isUpdateDownloaded = false
            refreshUpdateDialogUi()
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