package com.mmhw.csvtv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
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
                startActivity(Intent(requireContext(), SetupActivity::class.java))
                return
            }
            url.startsWith("rtmp://") -> {
                Log.d("MainFragment", "Opening PlaybackFragment directly for RTMP URL: $url")
                openPlaybackFragment(url)
                return
            }
            else -> {
                // Show a loading toast while resolving the URL
                Toast.makeText(requireContext(), "Resolving URL...", Toast.LENGTH_SHORT).show()

                // Resolve the URL to check if it’s a video stream
                Utils.resolveUrl(url) { resolvedUrl, contentType, error ->
                    if (!resolvedUrl.isNullOrBlank()) {
                        Log.d("MainFragment", "Resolved URL: $url -> $resolvedUrl, Content-Type: $contentType, isVideoStream=${Utils.isVideoStream(resolvedUrl, contentType)}")
                        if (Utils.isVideoStream(resolvedUrl, contentType)) {
                            Log.d("MainFragment", "Opening PlaybackFragment for resolved URL: $resolvedUrl")
                            openPlaybackFragment(resolvedUrl)
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

    private fun openPlaybackFragment(url: String) {
        val fragment = PlaybackFragment().apply {
            arguments = Bundle().apply {
                putString("video_url", url)
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
            updateRows()
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
    }
}