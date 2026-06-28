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

data class ResolvedMetadata(
    val resolvedUrl: String,
    val contentType: String?,
    val format: String?,
    val resolution: String?,
    val error: String? = null,
    val isAudioOnly: Boolean? = null,
    val audioChannels: String? = null
)

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
        if (sheetLink.startsWith("android.resource://") || sheetLink.startsWith("content://")) {
            // Handle local raw resource or content URI
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
                    it.contains("audio/") || // Audio content type (for radio/music channels)
                    it.contains("application/x-mpegurl") || // M3U8 specific content type
                    it.contains("application/vnd.apple.mpegurl") || // Another M3U8 specific content type
                    it.contains("application/octet-stream") // Octet-stream is highly likely raw video stream (like TS)
        }
        return false
    }

    // Watch frequency preferences
    fun incrementWatchCount(context: Context, url: String) {
        val prefs = context.getSharedPreferences("WatchPrefs", Context.MODE_PRIVATE)
        val count = prefs.getInt(url, 0)
        prefs.edit().putInt(url, count + 1).apply()
    }

    fun getWatchCount(context: Context, url: String): Int {
        val prefs = context.getSharedPreferences("WatchPrefs", Context.MODE_PRIVATE)
        return prefs.getInt(url, 0)
    }

    fun purgeWatchCount(context: Context) {
        val prefs = context.getSharedPreferences("WatchPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        // Also clear persistent resolution cache when resetting watch frequency
        clearPersistentResolutionCache(context)
    }

    // Short URL detection
    fun isShortUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("bit.ly/") ||
               lower.contains("tinyurl.com/") ||
               lower.contains("t.co/") ||
               lower.contains("goo.gl/") ||
               lower.contains("rebrand.ly/") ||
               lower.contains("shorturl.at/")
    }

    // Persistent redirect resolution cache
    fun getPersistentResolvedMetadata(context: Context, url: String): ResolvedMetadata? {
        val prefs = context.getSharedPreferences("ResolutionCachePrefs", Context.MODE_PRIVATE)
        val resolvedUrl = prefs.getString("${url}_resolved", null) ?: return null
        val contentType = prefs.getString("${url}_type", null)
        val format = prefs.getString("${url}_format", null)
        val resolution = prefs.getString("${url}_resolution", null)
        val isAudioOnly = if (prefs.contains("${url}_is_audio_only")) prefs.getBoolean("${url}_is_audio_only", false) else null
        val audioChannels = prefs.getString("${url}_audio_channels", null)
        return ResolvedMetadata(resolvedUrl, contentType, format, resolution, null, isAudioOnly, audioChannels)
    }

    fun savePersistentResolvedMetadata(context: Context, url: String, meta: ResolvedMetadata) {
        val prefs = context.getSharedPreferences("ResolutionCachePrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString("${url}_resolved", meta.resolvedUrl)
            .putString("${url}_type", meta.contentType)
            .putString("${url}_format", meta.format)
            .putString("${url}_resolution", meta.resolution)
        if (meta.isAudioOnly != null) {
            editor.putBoolean("${url}_is_audio_only", meta.isAudioOnly)
        }
        if (meta.audioChannels != null) {
            editor.putString("${url}_audio_channels", meta.audioChannels)
        }
        editor.apply()
    }

    fun saveAudioMetadata(context: Context, url: String, isAudioOnly: Boolean, audioChannels: String?) {
        val prefs = context.getSharedPreferences("ResolutionCachePrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("${url}_is_audio_only", isAudioOnly)
            .putString("${url}_audio_channels", audioChannels)
            .apply()
    }

    fun clearPersistentResolutionCache(context: Context) {
        val prefs = context.getSharedPreferences("ResolutionCachePrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    // Format & Resolution Parsers
    fun determineVideoFormat(url: String, contentType: String?): String? {
        val normalizedUrl = url.trim().lowercase()
        if (normalizedUrl.endsWith(".m3u8") || normalizedUrl.contains(".m3u8?") || contentType?.contains("mpegurl") == true) {
            return "M3U8"
        }
        if (normalizedUrl.endsWith(".mp4") || contentType?.contains("video/mp4") == true) {
            return "MP4"
        }
        if (normalizedUrl.endsWith(".ts") || contentType?.contains("video/mp2t") == true) {
            return "TS"
        }
        if (normalizedUrl.startsWith("rtmp://")) {
            return "RTMP"
        }
        if (contentType != null) {
            val subtype = contentType.substringAfter("video/", "").substringBefore(";", "").trim().uppercase()
            if (subtype.isNotEmpty()) {
                return subtype
            }
        }
        return "LINK"
    }

    fun parseResolution(body: String?): String? {
        if (body == null) return null
        val regex = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(body)
        var maxTargetHeight = 0
        for (match in matches) {
            val height = match.groupValues[2].toIntOrNull() ?: 0
            if (height > maxTargetHeight) {
                maxTargetHeight = height
            }
        }
        return if (maxTargetHeight > 0) "${maxTargetHeight}p" else null
    }

    // In-memory cache
    private val urlCache = mutableMapOf<String, ResolvedMetadata>()

    // Upgraded resolveUrl with caching & format/resolution sniffing
    fun resolveUrl(
        url: String,
        context: Context? = null,
        bypassCache: Boolean = false,
        callback: (String?, String?, String?, String?, String?, Boolean?, String?) -> Unit
    ) {
        if (url.isBlank()) {
            callback(null, null, null, null, "URL is empty", null, null)
            return
        }

        if (!bypassCache) {
            // 1. Check in-memory cache first
            urlCache[url]?.let { meta ->
                Log.d("Utils", "Using cached resolved URL: $url -> ${meta.resolvedUrl} (Format: ${meta.format}, Resolution: ${meta.resolution})")
                callback(meta.resolvedUrl, meta.contentType, meta.format, meta.resolution, meta.error, meta.isAudioOnly, meta.audioChannels)
                return
            }

            // 2. Check persistent cache if context is provided and it is a short URL
            if (context != null && isShortUrl(url)) {
                val cachedMeta = getPersistentResolvedMetadata(context, url)
                if (cachedMeta != null) {
                    Log.d("Utils", "Using persistent cached resolved URL: $url -> ${cachedMeta.resolvedUrl}")
                    urlCache[url] = cachedMeta
                    callback(cachedMeta.resolvedUrl, cachedMeta.contentType, cachedMeta.format, cachedMeta.resolution, null, cachedMeta.isAudioOnly, cachedMeta.audioChannels)
                    return
                }
            }
        }

        // 3. Perform network resolution
        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("Utils", "Failed to resolve URL: $url", e)
                val meta = ResolvedMetadata(url, null, null, null, "Failed to resolve URL: ${e.message}")
                urlCache[url] = meta
                callback(null, null, null, null, meta.error, null, null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val resolvedUrl = response.request.url.toString()
                val contentType = response.header("Content-Type")
                Log.d("Utils", "Resolved URL: $url -> $resolvedUrl (Content-Type: $contentType, HTTP Status: ${response.code})")

                if (resolvedUrl.isNullOrBlank()) {
                    val meta = ResolvedMetadata(url, null, null, null, "Failed to resolve URL: No valid URL found after redirects or initial request.")
                    urlCache[url] = meta
                    callback(null, null, null, null, meta.error, null, null)
                    return
                }

                val format = determineVideoFormat(resolvedUrl, contentType)
                val isAudio = contentType?.startsWith("audio/", ignoreCase = true) == true

                // If Content-Type is available and indicates a stream from HEAD request, complete resolution.
                if (contentType != null && isVideoStream(resolvedUrl, contentType)) {
                    val meta = ResolvedMetadata(resolvedUrl, contentType, format, null, null, isAudio, null)
                    urlCache[url] = meta
                    if (context != null && isShortUrl(url)) {
                        savePersistentResolvedMetadata(context, url, meta)
                    }
                    callback(resolvedUrl, contentType, format, null, null, isAudio, null)
                    return
                }

                // Otherwise, try GET request to sniff content type or body for HLS playlist resolution
                val getRequest = Request.Builder()
                    .url(resolvedUrl)
                    .get()
                    .build()

                client.newCall(getRequest).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        Log.e("Utils", "Failed to fetch GET for Content-Type check: $resolvedUrl", e)
                        val meta = ResolvedMetadata(resolvedUrl, contentType, format, null, "Failed to get full content for type check: ${e.message}")
                        urlCache[url] = meta
                        callback(resolvedUrl, contentType, format, null, meta.error, null, null)
                    }

                    override fun onResponse(call: okhttp3.Call, getResponse: okhttp3.Response) {
                        val finalContentType = getResponse.header("Content-Type") ?: contentType
                        
                        val responseBody = getResponse.body?.let { body ->
                            try {
                                val source = body.source()
                                source.request(8192)
                                val buffer = source.buffer
                                val byteCount = minOf(buffer.size, 8192L)
                                buffer.clone().readUtf8(byteCount)
                            } catch (e: Exception) {
                                Log.e("Utils", "Error reading response body preview: ${e.message}")
                                null
                            } finally {
                                getResponse.close()
                            }
                        }

                        val isStreamBasedOnBody = responseBody?.contains("#EXTM3U") == true || responseBody?.contains("#EXTINF") == true
                        val finalResolvedUrl = getResponse.request.url.toString()

                        val determinedContentType = if (isStreamBasedOnBody) "application/x-mpegurl" else finalContentType
                        val determinedFormat = determineVideoFormat(finalResolvedUrl, determinedContentType)
                        val parsedResolution = parseResolution(responseBody)
                        val isAudio = determinedContentType?.startsWith("audio/", ignoreCase = true) == true

                        Log.d("Utils", "GET response for $resolvedUrl -> Final Content-Type: $determinedContentType, Is stream: $isStreamBasedOnBody, Resolution: $parsedResolution, HTTP Status: ${getResponse.code}")

                        val finalError = if (!getResponse.isSuccessful && !isStreamBasedOnBody && !isVideoStream(finalResolvedUrl, determinedContentType)) {
                            "Content at resolved URL not successfully loaded: HTTP ${getResponse.code}"
                        } else {
                            null
                        }

                        val meta = ResolvedMetadata(finalResolvedUrl, determinedContentType, determinedFormat, parsedResolution, finalError, isAudio, null)
                        urlCache[url] = meta
                        if (context != null && isShortUrl(url) && finalError == null) {
                            savePersistentResolvedMetadata(context, url, meta)
                        }
                        callback(finalResolvedUrl, determinedContentType, determinedFormat, parsedResolution, finalError, isAudio, null)
                    }
                })
            }
        })
    }

    fun getDomainName(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = java.net.URI(url.trim())
            val host = uri.host
            if (host != null) {
                if (host.startsWith("www.", ignoreCase = true)) host.substring(4) else host
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
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