package com.mmhw.csvtv

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MainFragment"

class MainFragment : BrowseSupportFragment() {
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var videos: List<Video> = emptyList()
    private var selectedGroup: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateDateTime()
            handler.postDelayed(this, 60_000) // Update every minute
        }
    }
    private var isViewCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupUI()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isViewCreated = true
        loadData() // Load data after the view is created
    }

    private fun setupUI() {
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        adapter = rowsAdapter

        // Start updating the date and time
        updateDateTime()
        handler.post(updateTimeRunnable)

        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, row ->
            Log.d(TAG, "Item clicked: ${item.javaClass.name}, value: $item")
            when (item) {
                is Video -> {
                    Log.d(TAG, "Video clicked: url='${item.url}', length=${item.url?.length}")
                    val url = item.url?.trim() ?: ""
                    val title = item.title?.trim() ?: ""
                    if (url.equals("settings", ignoreCase = true) || title.equals("Settings", ignoreCase = true)) {
                        Log.d(TAG, "Settings item clicked")
                        val sharedPrefs = requireActivity().getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE)
                        sharedPrefs.edit().apply {
                            remove("sheet_link")
                            remove("sheet_id")
                            apply()
                        }
                        startActivity(Intent(requireContext(), SetupActivity::class.java))
                        requireActivity().finish()
                    } else if (Utils.isVideoStream(url)) {
                        Log.d(TAG, "Opening PlaybackFragment for url: $url")
                        val fragment = PlaybackFragment().apply {
                            arguments = Bundle().apply {
                                putString("video_url", url)
                            }
                        }
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, fragment)
                            .addToBackStack(null)
                            .commit()
                    } else {
                        Log.d(TAG, "Opening WebViewFragment for url: $url")
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
                }
                is HeaderItem -> {
                    Log.d(TAG, "Header clicked: ${item.name}")
                    if (item.name != "Settings") {
                        selectedGroup = item.name
                        // Find the row index for the selected group and set it as the selected row
                        val rowIndex = rowsAdapter.indexOf(row)
                        setSelectedPosition(rowIndex, true)
                    }
                }
            }
        })
    }

    private fun updateDateTime() {
        val currentDateTime = SimpleDateFormat("EEEE, MMM d, HH:mm", Locale.getDefault()).format(Date())
        title = currentDateTime
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isViewCreated = false
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateTimeRunnable)
    }

    private fun loadData() {
        val sharedPrefs = requireContext().getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE)
        val sheetLink = sharedPrefs.getString("sheet_link", null)
        val defaultCsvLink = "android.resource://${requireContext().packageName}/raw/default_csv"

        if (sheetLink != null) {
            // Try loading the saved sheet link first
            Log.d(TAG, "Fetching sheet data from saved link: $sheetLink")
            Utils.fetchSheetData(requireContext(), sheetLink) { videos, error ->
                if (!isAdded || !isViewCreated) {
                    Log.w(TAG, "Fragment not attached or view not created, skipping UI update")
                    return@fetchSheetData
                }
                if (error != null) {
                    Log.e(TAG, "Error fetching sheet data from saved link: $error")
                    // If the saved sheet link fails, fall back to the default CSV file
                    Log.d(TAG, "Falling back to default CSV: $defaultCsvLink")
                    loadDefaultCsv(defaultCsvLink)
                } else {
                    Log.d(TAG, "Fetched videos from saved link: $videos")
                    this.videos = videos
                    updateRows()
                }
            }
        } else {
            // No saved sheet link, use the default CSV file
            Log.d(TAG, "No saved sheet link, using default CSV: $defaultCsvLink")
            loadDefaultCsv(defaultCsvLink)
        }
    }

    private fun loadDefaultCsv(defaultCsvLink: String) {
        Utils.fetchSheetData(requireContext(), defaultCsvLink) { videos, error ->
            if (!isAdded || !isViewCreated) {
                Log.w(TAG, "Fragment not attached or view not created, skipping UI update")
                return@fetchSheetData
            }
            if (error != null) {
                Log.e(TAG, "Error fetching default CSV: $error")
                // If the default CSV also fails, show only the Settings row
                showSettingsOnly()
            } else {
                Log.d(TAG, "Fetched videos from default CSV: $videos")
                this.videos = videos
                updateRows()
            }
        }
    }

    private fun showSettingsOnly() {
        rowsAdapter.clear()
        val header = HeaderItem(0, "Settings")
        val rowAdapter = ArrayObjectAdapter(CardPresenter(this@MainFragment)).apply {
            add(Video("Settings", "Settings", null))
            Log.d(TAG, "Added Settings row with Video(url=settings, title=Settings)")
        }
        rowsAdapter.add(ListRow(header, rowAdapter))
        // Delay setSelectedPosition to ensure the view is ready
        handler.post {
            if (isAdded && isViewCreated) {
                setSelectedPosition(0, true) // Select the Settings row
            }
        }
    }

    private fun updateRows() {
        rowsAdapter.clear()

        val groupNames = videos.map { it.groupName }.distinct().sorted()
        Log.d(TAG, "Group names: $groupNames")
        val drawerItems = groupNames + listOf("Settings")

        drawerItems.forEachIndexed { index, item ->
            val header = HeaderItem(index.toLong(), item)
            val rowAdapter = if (item == "Settings") {
                ArrayObjectAdapter(CardPresenter(this@MainFragment)).apply {
                    add(Video("Settings", "Settings", null))
                    Log.d(TAG, "Added Settings row with Video(url=settings, title=Settings)")
                }
            } else {
                ArrayObjectAdapter(CardPresenter(this@MainFragment)).apply {
                    val filteredVideos = videos.filter { it.groupName == item }
                    if (filteredVideos.isEmpty()) {
                        add(Video("", "No videos available for this group.", null))
                    } else {
                        filteredVideos.forEach { video ->
                            add(video)
                        }
                    }
                    Log.d(TAG, "Added row for group $item with videos: $filteredVideos")
                }
            }

            rowsAdapter.add(ListRow(header, rowAdapter))
        }

        // Set the initial selected group and row
        if (selectedGroup == null && groupNames.isNotEmpty()) {
            selectedGroup = groupNames.first()
            setSelectedPosition(0, true) // Select the first row (first group)
        } else if (selectedGroup != null) {
            // Find the row index for the selected group and set it as the selected row
            val rowIndex = drawerItems.indexOf(selectedGroup)
            if (rowIndex != -1) {
                setSelectedPosition(rowIndex, true)
            }
        }
    }
}