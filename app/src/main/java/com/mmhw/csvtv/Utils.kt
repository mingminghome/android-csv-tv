package com.mmhw.csvtv

import android.content.Context
import android.net.Uri
import android.util.Log
import com.opencsv.CSVReader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.StringReader
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Utils {
    private val client = OkHttpClient()
    private val insecureClient = createInsecureOkHttpClient()

    private fun createInsecureOkHttpClient(): OkHttpClient {
        val trustAllCerts = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllCerts), SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun fetchSheetData(context: Context, sheetLink: String, callback: (List<Video>, String?) -> Unit) {
        when {
            sheetLink.startsWith("android.resource://") -> {
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
            }
            sheetLink.startsWith("content://") -> {
                try {
                    Log.d("Utils", "Reading local storage CSV from: $sheetLink")
                    val uri = Uri.parse(sheetLink)
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream == null) {
                        Log.e("Utils", "Failed to open input stream for URI: $sheetLink")
                        callback(emptyList(), "Failed to read local CSV: Input stream is null")
                        return
                    }
                    val csvData = inputStream.bufferedReader().use { it.readText() }
                    inputStream.close()
                    Log.d("Utils", "Successfully read local storage CSV data: ${csvData.take(100)}...")
                    parseCsvData(csvData, callback)
                } catch (e: Exception) {
                    Log.e("Utils", "Error reading local storage CSV", e)
                    callback(emptyList(), "Failed to read local CSV: ${e.message}")
                }
            }
            sheetLink.startsWith("file://") -> {
                try {
                    Log.d("Utils", "Reading file system CSV from: $sheetLink")
                    val file = File(Uri.parse(sheetLink).path ?: return)
                    if (!file.exists() || !file.canRead()) {
                        Log.e("Utils", "File does not exist or is not readable: $sheetLink")
                        callback(emptyList(), "Failed to read file: File does not exist or is not readable")
                        return
                    }
                    val csvData = file.readText()
                    Log.d("Utils", "Successfully read file system CSV data: ${csvData.take(100)}...")
                    parseCsvData(csvData, callback)
                } catch (e: Exception) {
                    Log.e("Utils", "Error reading file system CSV", e)
                    callback(emptyList(), "Failed to read file: ${e.message}")
                }
            }
            else -> {
                val request = Request.Builder().url(sheetLink).build()
                client.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        Log.e("Utils", "Failed to fetch remote CSV", e)
                        if (e is javax.net.ssl.SSLHandshakeException && e.message?.contains("CertificateNotYetValidException") == true) {
                            Log.d("Utils", "Retrying with insecure client due to certificate validity issue")
                            insecureClient.newCall(request).enqueue(object : okhttp3.Callback {
                                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                    Log.e("Utils", "Insecure client failed to fetch remote CSV", e)
                                    callback(emptyList(), "Failed to fetch sheet data: ${e.message}. Please check your device's date and time settings.")
                                }

                                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                    if (!response.isSuccessful) {
                                        Log.e("Utils", "Insecure client remote CSV fetch failed: ${response.message}")
                                        callback(emptyList(), "Failed to fetch sheet data: ${response.message}. Please check your device's date and time settings.")
                                        return
                                    }
                                    val csvData = response.body?.string() ?: ""
                                    Log.d("Utils", "Successfully fetched remote CSV data with insecure client: ${csvData.take(100)}...")
                                    parseCsvData(csvData, callback)
                                }
                            })
                        } else {
                            callback(emptyList(), "Failed to fetch sheet data: ${e.message}")
                        }
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
    }

    fun parseCsvData(csvData: String, callback: (List<Video>, String?) -> Unit) {
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
        return url.endsWith(".mp4") || url.endsWith(".m3u8") || url.startsWith("rtmp://") ||
                url.endsWith(".m3u") || url.endsWith(".ts")
    }

    fun fetchM3uData(context: Context, m3uUrl: String, callback: (List<Video>, String?) -> Unit) {
        if (m3uUrl.startsWith("android.resource://")) {
            try {
                val uri = Uri.parse(m3uUrl)
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    callback(emptyList(), "Failed to read local M3U: Input stream is null")
                    return
                }
                val m3uData = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                parseM3uData(m3uData, callback)
            } catch (e: Exception) {
                callback(emptyList(), "Failed to read local M3U: ${e.message}")
            }
        } else {
            val request = Request.Builder().url(m3uUrl).build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    callback(emptyList(), "Failed to fetch M3U: ${e.message}")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (!response.isSuccessful) {
                        callback(emptyList(), "Failed to fetch M3U: ${response.message}")
                        return
                    }
                    val m3uData = response.body?.string() ?: ""
                    parseM3uData(m3uData, callback)
                }
            })
        }
    }

    private fun parseM3uData(m3uData: String, callback: (List<Video>, String?) -> Unit) {
        val videos = mutableListOf<Video>()
        try {
            val lines = m3uData.lines()
            var currentTitle: String? = null

            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#EXTM3U")) {
                    continue
                }
                if (trimmedLine.startsWith("#EXTINF")) {
                    val titleMatch = Regex(".*,(.*)$").find(trimmedLine)
                    currentTitle = titleMatch?.groupValues?.get(1) ?: "Unnamed Stream"
                } else if (!trimmedLine.startsWith("#") && trimmedLine.isNotBlank()) {
                    if (currentTitle != null) {
                        videos.add(Video(currentTitle, trimmedLine, null, "M3U Streams"))
                        currentTitle = null
                    }
                }
            }
            callback(videos, null)
        } catch (e: Exception) {
            callback(emptyList(), "Error parsing M3U: ${e.message}")
        }
    }
}