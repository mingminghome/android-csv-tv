package com.mmhw.csvtv

import android.content.Context
import com.opencsv.CSVReader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import android.net.Uri
import android.util.Log
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Utils {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .sslSocketFactory(createUnsafeSslContext().socketFactory, createUnsafeTrustManager())
        .hostnameVerifier { _, _ -> true }
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

    fun isVideoStream(url: String, contentType: String?): Boolean {
        val normalizedUrl = url.trim().lowercase()
        // Check for common video file extensions in the URL itself
        if (normalizedUrl.endsWith(".mp4") ||
            normalizedUrl.endsWith(".m3u8") ||
            normalizedUrl.endsWith(".ts") ||
            normalizedUrl.startsWith("rtmp://") ||
            normalizedUrl.contains(".m3u8?")) {
            return true
        }

        // Check Content-Type header if available
        contentType?.lowercase()?.let {
            return it.contains("video/") || // General video content type
                    it.contains("application/x-mpegurl") || // M3U8 specific content type
                    it.contains("application/vnd.apple.mpegurl") // Another M3U8 specific content type
        }
        return false
    }

    private val urlCache = mutableMapOf<String, Pair<String, String?>>()

    fun resolveUrl(url: String, callback: (String?, String?, String?) -> Unit) {
        if (url.isBlank()) {
            callback(null, null, "URL is empty")
            return
        }

        urlCache[url]?.let { (resolved, type) ->
            Log.d("Utils", "Using cached resolved URL: $url -> $resolved (Type: $type)")
            callback(resolved, type, null)
            return
        }

        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("Utils", "Failed to resolve URL: $url", e)
                callback(null, null, "Failed to resolve URL: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val resolvedUrl = response.request.url.toString()
                val contentType = response.header("Content-Type")
                Log.d("Utils", "Resolved URL: $url -> $resolvedUrl (Content-Type: $contentType, HTTP Status: ${response.code})")

                // If resolvedUrl is null or blank, it's a true resolution failure.
                if (resolvedUrl.isNullOrBlank()) {
                    callback(null, null, "Failed to resolve URL: No valid URL found after redirects or initial request.")
                    return
                }

                // If Content-Type is available and already indicates a video stream from HEAD request, we're good.
                if (contentType != null && isVideoStream(resolvedUrl, contentType)) {
                    urlCache[url] = Pair(resolvedUrl, contentType)
                    callback(resolvedUrl, contentType, null) // No error
                    return
                }

                // Otherwise, try a GET request to sniff content type or body for M3U8 patterns
                val getRequest = Request.Builder()
                    .url(resolvedUrl)
                    .get()
                    .build()

                client.newCall(getRequest).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        Log.e("Utils", "Failed to fetch GET for Content-Type check: $resolvedUrl", e)
                        urlCache[url] = Pair(resolvedUrl, contentType)
                        callback(resolvedUrl, contentType, "Failed to get full content for type check: ${e.message}")
                    }

                    override fun onResponse(call: okhttp3.Call, getResponse: okhttp3.Response) {
                        val finalContentType = getResponse.header("Content-Type") ?: contentType
                        val responseBody = getResponse.body?.string()

                        val isStreamBasedOnBody = responseBody?.contains("#EXTM3U") == true || responseBody?.contains("#EXTINF") == true
                        val finalResolvedUrl = getResponse.request.url.toString()

                        val determinedContentType = if (isStreamBasedOnBody) "application/x-mpegurl" else finalContentType

                        Log.d("Utils", "GET response for $resolvedUrl -> Final Content-Type: $determinedContentType, Is stream based on body: $isStreamBasedOnBody, HTTP Status: ${getResponse.code}")

                        // Determine final error based on whether content was successfully retrieved/identified as stream
                        val finalError = if (!getResponse.isSuccessful && !isStreamBasedOnBody && !isVideoStream(finalResolvedUrl, determinedContentType)) {
                            "Content at resolved URL not successfully loaded: HTTP ${getResponse.code}"
                        } else {
                            null
                        }

                        urlCache[url] = Pair(finalResolvedUrl, determinedContentType)
                        callback(finalResolvedUrl, determinedContentType, finalError)
                    }
                })
            }
        })
    }


    private fun createUnsafeSslContext(): SSLContext {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(createUnsafeTrustManager()), SecureRandom())
        return sslContext
    }

    private fun createUnsafeTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}