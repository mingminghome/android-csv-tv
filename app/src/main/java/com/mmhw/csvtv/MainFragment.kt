package com.mmhw.csvtv

import android.content.Intent
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
            if (video != null && urlLower != "settings" && urlLower != "refresh") {
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
                    if (!resolvedUrl.isNullOrBlank()) {
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

    private fun openWebViewFragment(url: String) {
        val fragment = WebViewFragment().apply {
            arguments = Bundle().apply {
                putString("url", url)
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
        val defaultCsvLink = "android.resource://${requireContext().packageName}/raw/default_csv"

        val linkToUse = sheetLink ?: defaultCsvLink

        Utils.fetchSheetData(requireContext(), linkToUse) { videos, error ->
            if (error != null) {
                if (sheetLink != null) {
                    Utils.fetchSheetData(requireContext(), defaultCsvLink) { defaultVideos, defaultError ->
                        processVideosData(defaultVideos, defaultError)
                    }
                } else {
                    processVideosData(videos, error)
                }
            } else {
                processVideosData(videos, null)
            }
        }
    }

    private fun processVideosData(videos: List<Video>?, error: String?) {
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
    }

    private fun startVideoPrecheck() {
        if (!isAdded || isDetached) return
        
        // Gather all unique videos (by url) to check, excluding refresh/settings
        val uniqueVideos = videos.filter {
            val urlLower = it.url.trim().lowercase()
            urlLower != "settings" && urlLower != "refresh"
        }.distinctBy { it.url }

        // Sort by watch count descending
        val checkQueue = uniqueVideos.sortedByDescending {
            Utils.getWatchCount(requireContext(), it.url)
        }.toMutableList()

        checkNextVideo(checkQueue)
    }

    private fun checkNextVideo(queue: MutableList<Video>) {
        if (queue.isEmpty() || !isAdded || isDetached) return

        val video = queue.removeAt(0)
        video.isChecking = true
        updateVideoCard(video)

        val startTime = System.currentTimeMillis()
        val context = context ?: return

        Utils.resolveUrl(video.url, context) { resolvedUrl, contentType, format, resolution, error, isAudioOnly, audioChannels ->
            val ping = System.currentTimeMillis() - startTime
            
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                
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
                    checkNextVideo(queue)
                }, 500)
            }
        }
    }

    private fun updateVideoCard(video: Video) {
        for (i in 0 until rowsAdapter.size()) {
            val row = rowsAdapter.get(i) as? ListRow ?: continue
            val rowAdapter = row.adapter as? ArrayObjectAdapter ?: continue
            for (j in 0 until rowAdapter.size()) {
                val item = rowAdapter.get(j) as? Video ?: continue
                if (item.url == video.url) {
                    rowAdapter.replace(j, video)
                }
            }
        }
    }

    private fun refreshFocusedVideoMetadata(video: Video) {
        if (!isAdded || isDetached) return
        val startTime = System.currentTimeMillis()
        val context = context ?: return

        Utils.resolveUrl(video.url, context, bypassCache = true) { resolvedUrl, contentType, format, resolution, error, isAudioOnly, audioChannels ->
            val ping = System.currentTimeMillis() - startTime
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                if (focusedVideo?.url == video.url) {
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
    }

    private fun showSettingsAndRefreshOnly() {
        rowsAdapter.clear()
        val settingsHeader = HeaderItem(0, "Settings")
        val settingsRowAdapter = ArrayObjectAdapter(CardPresenter(this)).apply {
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
        handler.removeCallbacks(updateTimeRunnable)
        focusedVideoRefreshHandler.removeCallbacksAndMessages(null)
    }
}