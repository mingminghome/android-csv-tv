package com.mmhw.csvtv

import android.content.Context
import com.opencsv.CSVReader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import android.net.Uri
import android.util.Log
import java.io.IOException

object Utils {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun fetchSheetData(context: Context, sheetLink: String, callback: (List<Video>, String?) -> Unit) {
        if (sheetLink.startsWith("android.resource://")) {
            // Handle local raw resource
            try {
                Log.d("Utils", "Reading local CSV from: $sheetLink")
                val uri = Uri.parse(sheetLink)
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Log.e("Utils", "Failed to open input stream for URI: $sheetLink")
                    callback(emptyList(), "Failed to read local CSV: Input stream is null")
                    return
                }
                val csvData = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                Log.d("Utils", "Successfully read local CSV data: ${csvData.take(100)}...")
                parseCsvData(csvData, callback)
            } catch (e: Exception) {
                Log.e("Utils", "Error reading local CSV", e)
                callback(emptyList(), "Failed to read local CSV: ${e.message}")
            }
        } else {
            // Handle remote URL
            val request = Request.Builder().url(sheetLink).build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.e("Utils", "Failed to fetch remote CSV", e)
                    callback(emptyList(), "Failed to fetch sheet data: ${e.message}")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (!response.isSuccessful) {
                        Log.e("Utils", "Remote CSV fetch failed: ${response.message}")
                        callback(emptyList(), "Failed to fetch sheet data: ${response.message}")
                        return
                    }

                    val csvData = response.body?.string() ?: ""
                    Log.d("Utils", "Successfully fetched remote CSV data: ${csvData.take(100)}...")
                    parseCsvData(csvData, callback)
                }
            })
        }
    }

    private fun parseCsvData(csvData: String, callback: (List<Video>, String?) -> Unit) {
        val videos = mutableListOf<Video>()

        try {
            val csvReader = CSVReader(StringReader(csvData))
            val headers = csvReader.readNext()
            if (headers == null) {
                Log.e("Utils", "CSV is empty or invalid")
                callback(emptyList(), "Invalid CSV format: Empty file")
                return
            }
            val titleIndex = headers.indexOf("title")
            val urlIndex = headers.indexOf("url")
            val thumbnailUrlIndex = headers.indexOf("thumbnailUrl")
            val groupNameIndex = headers.indexOf("groupName")

            if (titleIndex == -1 || urlIndex == -1 || groupNameIndex == -1) {
                Log.e("Utils", "Invalid CSV format: Missing required columns (title, url, groupName)")
                callback(emptyList(), "Invalid CSV format: Missing required columns")
                return
            }

            var row: Array<String>?
            while (csvReader.readNext().also { row = it } != null) {
                row?.let {
                    val title = if (titleIndex < it.size) it[titleIndex] else ""
                    val url = if (urlIndex < it.size) it[urlIndex] else ""
                    val thumbnailUrl = if (thumbnailUrlIndex != -1 && thumbnailUrlIndex < it.size) it[thumbnailUrlIndex] else null
                    val groupName = if (groupNameIndex < it.size) it[groupNameIndex] else "Default"

                    if (title.isNotBlank() && url.isNotBlank()) {
                        videos.add(Video(title, url, thumbnailUrl, groupName))
                    }
                }
            }
            Log.d("Utils", "Parsed ${videos.size} videos from CSV")
            callback(videos, null)
        } catch (e: Exception) {
            Log.e("Utils", "Error parsing CSV", e)
            callback(emptyList(), "Error parsing CSV: ${e.message}")
        }
    }

    fun isVideoStream(url: String): Boolean {
        val normalizedUrl = url.trim().lowercase()
        return normalizedUrl.endsWith(".mp4") ||
                normalizedUrl.endsWith(".m3u8") ||
                normalizedUrl.endsWith(".ts") ||
                normalizedUrl.startsWith("rtmp://") ||
                normalizedUrl.contains(".m3u8?")
    }

    /**
     * Resolves a potentially shortened URL to its final destination.
     * @param url The input URL (e.g., shortened or direct).
     * @param callback Callback to return the resolved URL or an error message.
     */
    private val urlCache = mutableMapOf<String, String>()

    fun resolveUrl(url: String, callback: (String?, String?) -> Unit) {
        if (url.isBlank()) {
            callback(null, "URL is empty")
            return
        }

        // Skip resolution if already a video stream
        if (isVideoStream(url)) {
            Log.d("Utils", "URL is already a video stream: $url")
            callback(url, null)
            return
        }

        // Check cache first
        urlCache[url]?.let {
            Log.d("Utils", "Using cached resolved URL: $url -> $it")
            callback(it, null)
            return
        }

        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("Utils", "Failed to resolve URL: $url", e)
                callback(null, "Failed to resolve URL: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    Log.e("Utils", "URL resolution failed for $url: ${response.message}")
                    callback(null, "Failed to resolve URL: ${response.message}")
                    return
                }

                // The final URL after redirects
                val resolvedUrl = response.request.url.toString()
                Log.d("Utils", "Resolved URL: $url -> $resolvedUrl")
                callback(resolvedUrl, null)
            }
        })
    }
}