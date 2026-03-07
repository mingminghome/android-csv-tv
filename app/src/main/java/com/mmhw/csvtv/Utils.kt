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
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .sslSocketFactory(createUnsafeSslContext().socketFactory, createUnsafeTrustManager())
        .hostnameVerifier { _, _ -> true }
        .build()

    private val urlCache = mutableMapOf<String, Pair<String, String?>>()

    fun fetchSheetData(context: Context, sheetLink: String, callback: (List<Video>, String?) -> Unit) {
        if (sheetLink.startsWith("android.resource://")) {
            try {
                val uri = Uri.parse(sheetLink)
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    callback(emptyList(), "Failed to read local CSV: Input stream is null")
                    return
                }
                val csvData = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                parseCsvData(csvData, callback)
            } catch (e: Exception) {
                Log.e("Utils", "Error reading local CSV", e)
                callback(emptyList(), "Failed to read local CSV: ${e.message}")
            }
        } else {
            val request = Request.Builder().url(sheetLink).build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    callback(emptyList(), "Failed to fetch sheet data: ${e.message}")
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (!response.isSuccessful) {
                        callback(emptyList(), "Failed to fetch sheet data: ${response.message}")
                        return
                    }
                    val csvData = response.body?.string() ?: ""
                    parseCsvData(csvData, callback)
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

            if (titleIndex == -1 || urlIndex == -1 || groupNameIndex == -1) {
                callback(emptyList(), "Invalid CSV format")
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
            callback(videos, null)
        } catch (e: Exception) {
            callback(emptyList(), "Error parsing CSV: ${e.message}")
        }
    }

    fun isVideoStream(url: String, contentType: String?): Boolean {
        val normalizedUrl = url.trim().lowercase()
        if (normalizedUrl.contains(".mp4") || normalizedUrl.contains(".m3u8") ||
            normalizedUrl.contains(".ts") || normalizedUrl.contains(".flv") ||
            normalizedUrl.startsWith("rtmp://")) {
            return true
        }
        contentType?.lowercase()?.let {
            return it.contains("video/") || it.contains("mpegurl") || it.contains("video/x-flv")
        }
        return false
    }

    fun resolveUrl(context: Context, url: String, callback: (String?, String?, String?) -> Unit) {
        if (url.isBlank()) return callback(null, null, "URL is empty")

        urlCache[url]?.let { (resolved, type) ->
            return callback(resolved, type, null)
        }

        val request = Request.Builder().url(url).head().build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, null, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val resolvedUrl = response.request.url.toString()
                val contentType = response.header("Content-Type")

                if (contentType != null && isVideoStream(resolvedUrl, contentType)) {
                    urlCache[url] = Pair(resolvedUrl, contentType)
                    return callback(resolvedUrl, contentType, null)
                }

                if (contentType?.contains("text/html") == true) {
                    tryScrapeAndExtract(context, resolvedUrl, url, callback)
                } else {
                    tryGetStream(resolvedUrl, contentType, url, callback)
                }
            }
        })
    }

    private fun tryScrapeAndExtract(context: Context, resolvedUrl: String, originalUrl: String, callback: (String?, String?, String?) -> Unit) {
        val request = Request.Builder().url(resolvedUrl).get().build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                extractWithWebView(context, resolvedUrl, callback)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val html = response.body?.string() ?: ""
                val extracted = extractStreamFromHtml(html, resolvedUrl)
                if (extracted != null) {
                    urlCache[originalUrl] = Pair(extracted, "application/x-mpegurl")
                    callback(extracted, "application/x-mpegurl", null)
                } else {
                    extractWithWebView(context, resolvedUrl, callback)
                }
            }
        })
    }

    private fun extractStreamFromHtml(html: String, baseUrl: String): String? {
        try {
            val doc = Jsoup.parse(html, baseUrl)
            doc.select("video source, video").forEach {
                val src = it.attr("abs:src")
                if (isVideoStream(src, null)) return src
            }
            doc.select("iframe").forEach {
                val src = it.attr("abs:src")
                if (isVideoStream(src, null)) return src
            }
            val pattern = Pattern.compile("(https?://[\\w\\d./?=&%_-]+\\.(m3u8|mpd|flv)[\\w\\d./?=&%_-]*)")
            val matcher = pattern.matcher(html)
            if (matcher.find()) return matcher.group(1)
        } catch (e: Exception) {
            Log.e("Utils", "Scraping failed", e)
        }
        return null
    }

    private fun tryGetStream(resolvedUrl: String, contentType: String?, originalUrl: String, callback: (String?, String?, String?) -> Unit) {
        val request = Request.Builder().url(resolvedUrl).get().build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(resolvedUrl, contentType, e.message)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val finalUrl = response.request.url.toString()
                val finalType = response.header("Content-Type") ?: contentType
                if (isVideoStream(finalUrl, finalType)) {
                    urlCache[originalUrl] = Pair(finalUrl, finalType)
                    callback(finalUrl, finalType, null)
                } else {
                    callback(null, null, "Not a video stream")
                }
            }
        })
    }

    private fun extractWithWebView(context: Context, url: String, callback: (String?, String?, String?) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val webView = WebView(context)
                val isFound = AtomicBoolean(false)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val requestUrl = request?.url.toString()
                        if (isFound.get()) return null
                        
                        if (isVideoStream(requestUrl, null)) {
                            if (isFound.compareAndSet(false, true)) {
                                Log.d("Utils", "WebView caught stream: $requestUrl")
                                callback(requestUrl, "application/x-mpegurl", null)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    webView.stopLoading()
                                    webView.destroy()
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (isFound.get()) return
                        view?.evaluateJavascript("""
                            (function() {
                                var clickPlay = function(el) {
                                    var ev = document.createEvent('MouseEvents');
                                    ev.initEvent('click', true, true);
                                    el.dispatchEvent(ev);
                                };
                                var items = document.querySelectorAll('button, div, a, span');
                                for (var i = 0; i < items.length; i++) {
                                    var item = items[i];
                                    var txt = item.innerText.toLowerCase();
                                    if (txt.includes('play') || item.className.toLowerCase().includes('play')) {
                                        clickPlay(item);
                                    }
                                }
                                var v = document.querySelector('video');
                                if (v) v.play();
                            })();
                        """.trimIndent(), null)
                    }
                }

                webView.loadUrl(url)

                // 20s timeout cleanup
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isFound.compareAndSet(false, true)) {
                        callback(null, null, "Detection timed out")
                        webView.stopLoading()
                        webView.destroy()
                    }
                }, 20000)
            } catch (e: Exception) {
                Log.e("Utils", "WebView failed", e)
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