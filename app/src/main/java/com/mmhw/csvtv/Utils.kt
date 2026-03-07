package com.mmhw.csvtv

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.*
import com.opencsv.CSVReader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.io.StringReader
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Utils {
    private const val RESOLVE_TAG = "resolve_url_tag"
    private var activeWebView: WebView? = null
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .sslSocketFactory(createUnsafeSslContext().socketFactory, createUnsafeTrustManager())
        .hostnameVerifier { _, _ -> true }
        .build()

    private val urlCache = mutableMapOf<String, Pair<String, String?>>()

    fun cancelOngoingResolution() {
        for (call in client.dispatcher.queuedCalls()) {
            if (call.request().tag() == RESOLVE_TAG) call.cancel()
        }
        for (call in client.dispatcher.runningCalls()) {
            if (call.request().tag() == RESOLVE_TAG) call.cancel()
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            activeWebView?.let {
                it.stopLoading()
                it.destroy()
                activeWebView = null
            }
        }
    }

    fun fetchSheetData(context: Context, sheetLink: String, callback: (List<Video>, String?) -> Unit) {
        if (sheetLink.startsWith("android.resource://")) {
            try {
                val uri = Uri.parse(sheetLink)
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    callback(emptyList(), "Failed to read local CSV")
                    return
                }
                val csvData = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                parseCsvData(csvData, callback)
            } catch (e: Exception) {
                callback(emptyList(), "Local CSV error: ${e.message}")
            }
        } else {
            val request = Request.Builder().url(sheetLink).header("User-Agent", USER_AGENT).build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    callback(emptyList(), e.message)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        val csvData = it.body?.string() ?: ""
                        parseCsvData(csvData, callback)
                    }
                }
            })
        }
    }

    private fun parseCsvData(csvData: String, callback: (List<Video>, String?) -> Unit) {
        val videos = mutableListOf<Video>()
        try {
            val csvReader = CSVReader(StringReader(csvData))
            val headers = csvReader.readNext() ?: return callback(emptyList(), "Empty CSV")
            val titleIndex = headers.indexOf("title")
            val urlIndex = headers.indexOf("url")
            val thumbnailUrlIndex = headers.indexOf("thumbnailUrl")
            val groupNameIndex = headers.indexOf("groupName")

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
            callback(videos, null)
        } catch (e: Exception) {
            callback(emptyList(), e.message)
        }
    }

    fun isVideoStream(url: String, contentType: String?): Boolean {
        val normalizedUrl = url.trim().lowercase()
        val type = contentType?.lowercase() ?: ""
        
        // Dynamic detection based on broad patterns
        val hasStreamExt = normalizedUrl.contains(".m3u8") || normalizedUrl.contains(".mpd") || 
                          normalizedUrl.contains(".flv") || normalizedUrl.contains(".ts") || 
                          normalizedUrl.contains(".mp4") || normalizedUrl.startsWith("rtmp://")
        
        val isVideoType = type.contains("video/") || type.contains("mpegurl") || 
                         type.contains("apple.mpegurl") || type.contains("dash+xml")
        
        return (hasStreamExt || isVideoType) && !isStaticAsset(normalizedUrl)
    }

    private fun isStaticAsset(url: String): Boolean {
        return url.contains(".js") || url.contains(".css") || url.contains(".png") || 
               url.contains(".jpg") || url.contains(".ico") || url.contains("analytics")
    }

    fun inferMimeType(url: String, contentType: String?, sniffBytes: ByteArray?): String? {
        val type = contentType?.lowercase() ?: ""
        
        // 1. If it's a specific video type, trust it
        if (type.contains("video/") && !type.contains("octet-stream")) return type
        if (type.contains("mpegurl") || type.contains("dash+xml")) return type

        // 2. Check content signature if available
        sniffBytes?.let {
            val head = String(it.take(2048).toByteArray(), Charsets.UTF_8)
            if (head.contains("#EXTM3U")) return "application/x-mpegurl"
            if (head.contains("<?xml") && head.contains("<MPD")) return "application/dash+xml"
            if (isMpegTs(it)) return "video/mp2t"
        }

        // 3. Fallback to URL extension inference
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains(".m3u8") -> "application/x-mpegurl"
            lowerUrl.contains(".mpd") -> "application/dash+xml"
            lowerUrl.contains(".flv") -> "video/x-flv"
            lowerUrl.contains(".ts") -> "video/mp2t"
            lowerUrl.contains(".mp4") -> "video/mp4"
            lowerUrl.startsWith("rtmp://") -> "video/x-rtmp"
            else -> contentType // Last resort: return original
        }
    }

    private fun isMpegTs(bytes: ByteArray): Boolean {
        if (bytes.size < 188 * 4) return false
        for (i in 0 until 188) {
            if (bytes.size > i + 188 * 3 &&
                bytes[i] == 0x47.toByte() && 
                bytes[i + 188] == 0x47.toByte() && 
                bytes[i + 376] == 0x47.toByte()) return true
        }
        return false
    }

    fun resolveUrl(context: Context, url: String, callback: (String?, String?, String?) -> Unit) {
        if (url.isBlank()) return callback(null, null, "URL is empty")
        urlCache[url]?.let { (resolved, type) -> return callback(resolved, type, null) }

        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).tag(RESOLVE_TAG).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (e.message != "Canceled") callback(null, null, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        callback(null, null, "Server error: ${resp.code}")
                        return
                    }
                    
                    val resolvedUrl = resp.request.url.toString()
                    val contentType = resp.header("Content-Type")
                    
                    // Dynamic Content Sniffing (Peek 32KB)
                    val peekBody = resp.peekBody(32768)
                    val bytes = peekBody.bytes()
                    val mime = inferMimeType(resolvedUrl, contentType, bytes)

                    // If it's a confirmed video stream, return it
                    if (mime != null && isVideoStream(resolvedUrl, mime)) {
                        urlCache[url] = Pair(resolvedUrl, mime)
                        callback(resolvedUrl, mime, null)
                        return
                    }

                    // If it's HTML, we need to extract from page
                    val bodyString = String(bytes, Charsets.UTF_8)
                    if (mime?.contains("text/html") == true || bodyString.contains("<html", ignoreCase = true)) {
                        val extracted = extractStreamFromHtml(bodyString, resolvedUrl)
                        if (extracted != null) {
                            val extMime = inferMimeType(extracted, null, null)
                            urlCache[url] = Pair(extracted, extMime)
                            callback(extracted, extMime, null)
                        } else {
                            extractWithWebView(context, resolvedUrl, callback)
                        }
                    } else {
                        // Final dynamic fallback: Try playing as MPEG-TS if binary and unidentified
                        if (mime?.contains("octet-stream") == true) {
                            callback(resolvedUrl, "video/mp2t", null)
                        } else {
                            callback(null, null, "Unrecognized stream type")
                        }
                    }
                }
            }
        })
    }

    private fun extractStreamFromHtml(html: String, baseUrl: String): String? {
        try {
            val doc = Jsoup.parse(html, baseUrl)
            doc.select("video source, video, iframe").forEach {
                val src = it.attr("abs:src")
                if (src.isNotBlank() && isVideoStream(src, null)) return src
            }
            val pattern = Pattern.compile("(https?://[\\w\\d./?=&%_-]+\\.(m3u8|mpd|flv|ts|mp4)[\\w\\d./?=&%_-]*)")
            val matcher = pattern.matcher(html)
            if (matcher.find()) return matcher.group(1)
        } catch (e: Exception) {}
        return null
    }

    private fun extractWithWebView(context: Context, url: String, callback: (String?, String?, String?) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                activeWebView?.let { it.stopLoading(); it.destroy() }
                val webView = WebView(context)
                activeWebView = webView
                val isFound = AtomicBoolean(false)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = USER_AGENT
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url.toString()
                        if (isFound.get()) return null
                        if (isVideoStream(reqUrl, null) && !isStaticAsset(reqUrl)) {
                            if (isFound.compareAndSet(false, true)) {
                                val mime = inferMimeType(reqUrl, null, null)
                                callback(reqUrl, mime, null)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    if (activeWebView == webView) activeWebView = null
                                    webView.stopLoading(); webView.destroy()
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isFound.get()) return
                        view?.evaluateJavascript("(function(){document.querySelectorAll('button,div,a,span').forEach(e=>{var t=e.innerText.toLowerCase();if(t.includes('play')||e.className.toLowerCase().includes('play'))e.click()});if(document.querySelector('video'))document.querySelector('video').play();})()", null)
                    }
                }
                webView.loadUrl(url)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isFound.compareAndSet(false, true)) {
                        callback(null, null, "Detection timed out")
                        if (activeWebView == webView) activeWebView = null
                        webView.stopLoading(); webView.destroy()
                    }
                }, 25000)
            } catch (e: Exception) {
                callback(null, null, e.message)
            }
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