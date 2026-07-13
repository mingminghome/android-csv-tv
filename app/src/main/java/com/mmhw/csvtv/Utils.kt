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
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
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
            // Handle remote URL (with support for shortened URLs like tinyurl)
            // Always normalize Google sheet links (temp googleusercontent URLs can expire)
            var effectiveLink = normalizeGoogleSheetUrl(sheetLink)

            var linkToFetch = effectiveLink
            var usedCachedResolved = false
            if (isShortUrl(effectiveLink)) {
                val cached = getResolvedSheetLink(context, effectiveLink)
                if (cached != null) {
                    linkToFetch = normalizeGoogleSheetUrl(cached)
                    usedCachedResolved = true
                    Log.d("Utils", "Using cached resolved CSV link: $effectiveLink -> $linkToFetch")

                    // Proactively make sure main sheet_link is the (normalized) resolved one
                    val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    if (prefs.getString("sheet_link", null) != linkToFetch) {
                        prefs.edit().putString("sheet_link", linkToFetch).apply()
                    }
                } else {
                    Log.d("Utils", "Short URL for CSV detected, will resolve on fetch: $effectiveLink")
                }
            }

            val request = Request.Builder().url(linkToFetch).build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.e("Utils", "Failed to fetch remote CSV", e)

                    // If we used a cached resolved URL and it failed, invalidate the stale cache
                    // so next attempt will re-resolve from the original short URL.
                    if (usedCachedResolved && isShortUrl(sheetLink)) {
                        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        prefs.edit().remove(RESOLVED_SHEET_PREFIX + sheetLink).apply()
                        // Restore the original short link in case we had overwritten it with a now-dead resolved URL
                        prefs.edit().putString("sheet_link", sheetLink).apply()
                        Log.d("Utils", "Invalidated stale resolved sheet link for $sheetLink and restored original")
                    }

                    val errorDetail = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
                    callback(emptyList(), "Failed to fetch sheet data: $errorDetail")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (!response.isSuccessful) {
                        val reason = if (response.message.isNotBlank()) response.message else "No reason"
                        Log.e("Utils", "Remote CSV fetch failed: HTTP ${response.code} $reason")
                        callback(emptyList(), "Failed to fetch sheet data: HTTP ${response.code} $reason")
                        return
                    }

                    val finalUrl = response.request.url.toString()
                    val normalizedFinal = normalizeGoogleSheetUrl(finalUrl)

                    // Overwrite stored sheet_link with the (normalized) resolved final URL
                    if (isShortUrl(sheetLink) && normalizedFinal != sheetLink) {
                        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("sheet_link", normalizedFinal).apply()
                        Log.d("Utils", "Resolved short sheet link and overwrote stored sheet_link: $sheetLink → $normalizedFinal")

                        // Update the resolved cache with the stable normalized URL
                        saveResolvedSheetLink(context, sheetLink, normalizedFinal)
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

    /**
     * True for IPTV/gateway URLs that usually serve HLS/TS even when the path has no .m3u8
     * (e.g. `…/163189.php?id=viu`). Prefer native [PlaybackFragment] for these over WebView.
     */
    fun looksLikeIptvStreamUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.trim().lowercase()
        if (lower.startsWith("rtmp://")) return true
        if (lower.contains(".m3u8")) return true
        if (lower.endsWith(".mp4") || lower.contains(".mp4?")) return true
        if (lower.endsWith(".ts") || lower.contains(".ts?")) return true
        if (lower.contains("/live") || lower.contains("playlist")) return true
        // Common IPTV gateway shapes (php?id=…, streaming proxies)
        if (lower.contains(".php") && (lower.contains("id=") || lower.contains("channel") || lower.contains("stream"))) {
            return true
        }
        if (lower.contains("get.php") || lower.contains("live.php") || lower.contains("play.php")) return true
        return false
    }

    fun isVideoStream(url: String, contentType: String?): Boolean {
        val normalizedUrl = url.trim().lowercase()
        // Check for common video file extensions / stream URL shapes
        if (normalizedUrl.endsWith(".mp4") ||
            normalizedUrl.endsWith(".m3u8") ||
            normalizedUrl.endsWith(".ts") ||
            normalizedUrl.startsWith("rtmp://") ||
            normalizedUrl.contains(".m3u8?") ||
            normalizedUrl.contains(".mp4?") ||
            looksLikeIptvStreamUrl(normalizedUrl)
        ) {
            return true
        }

        // Check Content-Type header if available
        contentType?.lowercase()?.let {
            return it.contains("video/") || // General video content type
                    it.contains("audio/") || // Audio content type (for radio/music channels)
                    it.contains("application/x-mpegurl") || // M3U8 specific content type
                    it.contains("application/vnd.apple.mpegurl") || // Another M3U8 specific content type
                    it.contains("application/dash+xml") ||
                    it.contains("application/octet-stream") || // Octet-stream is highly likely raw video stream (like TS)
                    // Some gateways mislabel HLS as text/plain
                    (it.contains("text/plain") && looksLikeIptvStreamUrl(url))
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

    // Resolution + caching for shortened CSV sheet links (e.g. tinyurl).
    // On success we also overwrite the main "sheet_link" preference with the final resolved URL
    // (as requested). The separate cache acts as a backup for the current session.
    private const val SHEET_LINK_PREFS = "AppPrefs"
    private const val RESOLVED_SHEET_PREFIX = "resolved_sheet_link:"

    fun getResolvedSheetLink(context: Context, originalLink: String): String? {
        if (!isShortUrl(originalLink)) return originalLink
        val prefs = context.getSharedPreferences(SHEET_LINK_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(RESOLVED_SHEET_PREFIX + originalLink, null)
    }

    fun saveResolvedSheetLink(context: Context, originalLink: String, resolvedLink: String) {
        if (!isShortUrl(originalLink) || resolvedLink == originalLink) return
        val prefs = context.getSharedPreferences(SHEET_LINK_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(RESOLVED_SHEET_PREFIX + originalLink, resolvedLink).apply()
        Log.d("Utils", "Saved resolved sheet link: $originalLink -> $resolvedLink")
    }

    /**
     * Normalizes Google Sheets published CSV URLs to the stable permanent form.
     * The redirect targets from shorteners are often temporary signed googleusercontent
     * links that expire. We extract the 2PACX key and use the stable docs.google.com form.
     */
    private fun normalizeGoogleSheetUrl(url: String): String {
        if (!url.contains("googleusercontent.com") && !url.contains("docs.google.com")) {
            return url
        }
        // Extract the published key (2PACX-...)
        val keyMatch = Regex("2PACX-[A-Za-z0-9_-]+").find(url)
        if (keyMatch != null) {
            val key = keyMatch.value
            val stable = "https://docs.google.com/spreadsheets/d/e/$key/pub?output=csv"
            if (stable != url) {
                Log.d("Utils", "Normalized Google Sheet URL to stable form: $url -> $stable")
            }
            return stable
        }
        return url
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
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            callback(null, null, null, null, "URL is empty", null, null)
            return
        }

        val lowerUrl = trimmedUrl.lowercase()
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            val format = determineVideoFormat(trimmedUrl, null)
            callback(trimmedUrl, null, format, null, null, false, null)
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

                // If HEAD shows an error, check if we should fallback to GET instead of failing immediately.
                // 405 Method Not Allowed, 403 Forbidden, 501 Not Implemented might mean HEAD is blocked.
                val looksLikeVideo = determineVideoFormat(resolvedUrl, null) != null
                if (!response.isSuccessful && response.code != 405 && response.code != 403 && response.code != 501 && !looksLikeVideo) {
                    val meta = ResolvedMetadata(resolvedUrl, contentType, null, null, "Server returned HTTP ${response.code} (likely unreachable or blocked)")
                    urlCache[url] = meta
                    callback(resolvedUrl, contentType, null, null, meta.error, null, null)
                    return
                }

                val format = determineVideoFormat(resolvedUrl, contentType)
                val isAudio = contentType?.startsWith("audio/", ignoreCase = true) == true

                // If Content-Type is available and indicates a stream from HEAD request (and it was successful), complete resolution.
                if (response.isSuccessful && contentType != null && isVideoStream(resolvedUrl, contentType)) {
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

                        // Detect common cases where a stream URL actually redirects to a web page / error page.
                        // Do not treat as HTML if body is clearly an HLS playlist.
                        val looksLikeHtmlPage = !isStreamBasedOnBody && responseBody?.let { body ->
                            val lower = body.lowercase()
                            lower.contains("<html") ||
                            lower.contains("<!doctype") ||
                            lower.contains("<head") ||
                            lower.contains("cloudflare") ||
                            lower.contains("access denied") ||
                            lower.contains("forbidden") ||
                            lower.contains("not available") ||
                            lower.contains("stream not found") ||
                            lower.contains("login") ||
                            lower.contains("geo-block") ||
                            lower.contains("blocked")
                        } ?: false

                        // IPTV gateways (php?id=) often return intermittent HTML errors; still mark as stream
                        // so the native player can try (and recover) instead of opening a WebView player page.
                        val isRecognizedVideoStream = isStreamBasedOnBody ||
                            isVideoStream(finalResolvedUrl, determinedContentType) ||
                            (looksLikeIptvStreamUrl(finalResolvedUrl) && !looksLikeHtmlPage)

                        Log.d(
                            "Utils",
                            "GET response for $resolvedUrl -> Final Content-Type: $determinedContentType, " +
                                "Is stream: $isStreamBasedOnBody, Is HTML: $looksLikeHtmlPage, " +
                                "iptvLike=${looksLikeIptvStreamUrl(finalResolvedUrl)}, HTTP Status: ${getResponse.code}"
                        )

                        val finalError = if (!isRecognizedVideoStream) {
                            when {
                                looksLikeHtmlPage && !looksLikeIptvStreamUrl(finalResolvedUrl) ->
                                    "Resolved to web page instead of video stream (likely error/login/blocked page)"
                                // IPTV-like URL that landed on HTML: no hard error — caller may still try native
                                looksLikeHtmlPage && looksLikeIptvStreamUrl(finalResolvedUrl) -> null
                                !getResponse.isSuccessful -> "Content at resolved URL not successfully loaded: HTTP ${getResponse.code}"
                                else -> "URL did not resolve to a recognizable video or audio stream"
                            }
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

    /**
     * Checks GitHub latest release. On success with a newer version:
     * callback(version, apkUrl, releaseTitle, releaseNotes, null).
     * releaseTitle/notes may be blank if the release omitted them.
     */
    fun checkAppUpdate(
        context: Context,
        callback: (
            newVersionName: String?,
            apkUrl: String?,
            releaseTitle: String?,
            releaseNotes: String?,
            error: String?
        ) -> Unit
    ) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/mingminghome/android-csv-tv/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, null, null, null, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    callback(null, null, null, null, "Server error: ${response.code}")
                    return
                }

                val bodyString = response.body?.string()
                if (bodyString.isNullOrBlank()) {
                    callback(null, null, null, null, "Response body is empty")
                    return
                }

                try {
                    val json = org.json.JSONObject(bodyString)
                    val tagName = json.optString("tag_name", "")
                    if (tagName.isBlank()) {
                        callback(null, null, null, null, "No tag_name in release")
                        return
                    }

                    // Extract clean x.y.z version robustly.
                    // Supports "v1.2.0", "1.1.1", "release-v1.1.1", etc.
                    val afterDash = tagName.substringAfterLast("-")
                    val latestVersionName = afterDash.substringAfterLast("v").ifEmpty { afterDash }.trim()

                    // Get current version name
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersionName = packageInfo.versionName ?: "1.2.0"

                    val releaseTitle = json.optString("name", "").ifBlank { "v$latestVersionName" }
                    val releaseNotes = json.optString("body", "").trim().ifBlank { null }

                    Log.d("Utils", "Update check: current=$currentVersionName, latestFromTag=$latestVersionName (from tag=$tagName)")

                    if (isNewerVersion(currentVersionName, latestVersionName)) {
                        // Find release APK asset in the assets list
                        val assetsArray = json.optJSONArray("assets")
                        var apkUrl: String? = null
                        if (assetsArray != null) {
                            for (i in 0 until assetsArray.length()) {
                                val assetObj = assetsArray.getJSONObject(i)
                                val name = assetObj.optString("name", "")
                                if (name.endsWith(".apk")) {
                                    val downloadUrl = assetObj.optString("browser_download_url", "")
                                    if (downloadUrl.isNotEmpty()) {
                                        apkUrl = downloadUrl
                                        break
                                    }
                                }
                            }
                        }
                        if (apkUrl != null) {
                            callback(latestVersionName, apkUrl, releaseTitle, releaseNotes, null)
                        } else {
                            callback(null, null, null, null, "No APK asset found in latest release")
                        }
                    } else {
                        // Current version is up to date
                        callback(null, null, null, null, null)
                    }
                } catch (e: Exception) {
                    callback(null, null, null, null, "Failed to parse release info: ${e.message}")
                }
            }
        })
    }

    /** Light markdown cleanup for plain TextView release notes on TV. */
    fun formatReleaseNotesForDisplay(raw: String?): String {
        if (raw.isNullOrBlank()) return "No release notes available."
        return raw
            .replace("\r\n", "\n")
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE), "• ")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .trim()
    }

    fun isNewerVersion(current: String, latest: String): Boolean {
        fun parseVersion(v: String): List<Int> {
            val parts = v.replace("v", "").split(".").mapNotNull { it.toIntOrNull() }.toMutableList()
            while (parts.size < 3) parts.add(0) // ensure x.y.z format
            return parts
        }
        val currentParts = parseVersion(current)
        val latestParts = parseVersion(latest)
        
        for (i in 0 until 3) {
            val curr = currentParts[i]
            val lat = latestParts[i]
            if (lat > curr) return true
            if (curr > lat) return false
        }
        return false
    }

    fun isShowSourceDetailsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("show_source_details", false) // Disabled by default
    }

    fun setShowSourceDetailsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_source_details", enabled).apply()
    }

    // Default search engine for the Browser card
    fun getDefaultSearchEngine(context: Context): String {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getString("default_search_engine", "duckduckgo") ?: "duckduckgo"
    }

    fun setDefaultSearchEngine(context: Context, engine: String) {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("default_search_engine", engine).apply()
    }

    fun getSearchEngineUrl(context: Context): String {
        return getDefaultBrowserPage(context)
    }

    fun getSearchEngineDisplayName(engine: String): String {
        return when (engine.lowercase()) {
            "google" -> "Google"
            else -> "DuckDuckGo"
        }
    }

    fun getDefaultBrowserPage(context: Context): String {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getString("default_browser_page", "https://duckduckgo.com/") ?: "https://duckduckgo.com/"
    }

    fun setDefaultBrowserPage(context: Context, page: String) {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("default_browser_page", page).apply()
    }

    fun isUrlLike(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.contains(" ")) return false
        if (t.contains("://")) return true
        if (t.startsWith("www.", ignoreCase = true)) return true
        val dotIndex = t.lastIndexOf('.')
        if (dotIndex > 0 && dotIndex < t.length - 1) {
            val tld = t.substring(dotIndex + 1)
            if (tld.length in 2..6 && tld.all { it.isLetterOrDigit() }) {
                return true
            }
        }
        return false
    }

    fun buildSearchUrl(defaultPage: String, query: String): String {
        val encoded = try {
            java.net.URLEncoder.encode(query, "UTF-8")
        } catch (e: Exception) {
            query.replace(" ", "+")
        }
        return when {
            defaultPage.contains("duckduckgo", ignoreCase = true) -> "https://duckduckgo.com/?q=$encoded"
            defaultPage.contains("google", ignoreCase = true) -> "https://www.google.com/search?q=$encoded"
            else -> if (defaultPage.contains("?")) "$defaultPage&q=$encoded" else "$defaultPage?q=$encoded"
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