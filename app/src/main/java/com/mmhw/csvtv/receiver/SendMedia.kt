package com.mmhw.csvtv.receiver

/**
 * MIME / filename helpers for Send (photos, video files, and http(s) stream URLs).
 */
object SendMedia {
    private val httpUrl = Regex("""https?://[^\s<>"'\\]+""", RegexOption.IGNORE_CASE)

    private val imageExt = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif"
    )
    private val videoExt = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "avi", "ts", "m3u8", "mpd"
    )
    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "wma")
    private val mimeByExt = mapOf(
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heic",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "ts" to "video/mp2t",
        "m3u8" to "application/vnd.apple.mpegurl",
        "mpd" to "application/dash+xml",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/mp4",
        "flac" to "audio/flac"
    )

    fun isDirectHttpUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true)
    }

    /** First http(s) URL in shared text (share sheets often send title + URL). */
    fun extractHttpUrl(text: String): String? {
        val raw = httpUrl.find(text)?.value ?: return null
        return raw.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
    }

    fun isDirectMediaPath(pathOrUrl: String): Boolean {
        val ext = fileExt(pathOrUrl)
        return ext in imageExt || ext in videoExt || ext in audioExt
    }

    fun isWebPage(url: String, mime: String? = null): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (m == "text/html" || m == "application/xhtml+xml" || m == "text/x-castify-web" || m == "text/x-castlib-web" || m == "text/x-csvtv-web") {
            return true
        }
        val extracted = extractHttpUrl(url) ?: return false
        if (isDirectMediaPath(extracted)) return false
        return extracted.startsWith("http://", ignoreCase = true) ||
            extracted.startsWith("https://", ignoreCase = true)
    }

    /**
     * Resolve photo / video / audio / web for Receive.
     * A known [declared] kind from the native library wins; otherwise MIME then path.
     */
    fun mediaKind(
        uri: String,
        mime: String? = null,
        declared: Int = NativeReceiver.MEDIA_UNKNOWN
    ): Int {
        when (declared) {
            NativeReceiver.MEDIA_IMAGE,
            NativeReceiver.MEDIA_VIDEO,
            NativeReceiver.MEDIA_AUDIO,
            NativeReceiver.MEDIA_WEB -> return declared
        }
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            m.startsWith("image/") -> NativeReceiver.MEDIA_IMAGE
            m.startsWith("video/") || m.contains("mp2t") || m.contains("mpegurl") ||
                m.contains("dash+xml") -> NativeReceiver.MEDIA_VIDEO
            m.startsWith("audio/") -> NativeReceiver.MEDIA_AUDIO
            isWebPage(uri, mime) -> NativeReceiver.MEDIA_WEB
            else -> kindFromPath(uri)
        }
    }

    fun mimeOf(declared: String?, pathOrUrl: String): String {
        val d = declared?.substringBefore(';')?.trim().orEmpty().lowercase()
        val specific = d.isNotBlank() &&
            d != "*/*" &&
            !d.endsWith("/*") &&
            d != "application/octet-stream" &&
            d != "text/plain"
        if (specific) {
            return d
        }
        mimeByExt[fileExt(pathOrUrl)]?.let { return it }
        return when {
            d.startsWith("video/") -> "video/mp4"
            d.startsWith("image/") -> "image/jpeg"
            d.startsWith("audio/") -> "audio/mpeg"
            else -> "video/mp4"
        }
    }

    fun extensionOf(mime: String, pathOrUrl: String): String {
        val m = mime.lowercase()
        return when {
            m.contains("png") -> "png"
            m.contains("gif") -> "gif"
            m.contains("webp") -> "webp"
            m.contains("bmp") -> "bmp"
            m.contains("heic") || m.contains("heif") -> "heic"
            m.startsWith("image/") -> "jpg"
            m.contains("webm") -> "webm"
            m.contains("matroska") || m.contains("mkv") -> "mkv"
            m.contains("quicktime") -> "mov"
            m.contains("avi") -> "avi"
            m.contains("mp2t") || m == "video/mp2t" -> "ts"
            m.contains("mpegurl") || m.contains("m3u8") -> "m3u8"
            m.contains("dash") -> "mpd"
            m.startsWith("video/") -> "mp4"
            m.contains("flac") -> "flac"
            m.startsWith("audio/") -> "mp3"
            else -> {
                val path = pathOrUrl.substringBefore('?').substringAfterLast('.')
                if (path.length in 2..5 && path.all { it.isLetterOrDigit() }) path.lowercase() else "bin"
            }
        }
    }

    fun titleOf(pathOrUrl: String, fallback: String = "Media"): String {
        val last = pathOrUrl.substringBefore('?').substringAfterLast('/').substringAfterLast(':')
        return last.ifBlank { fallback }
    }

    fun isVideo(mime: String): Boolean {
        val m = mime.lowercase()
        return m.startsWith("video/") || m.contains("mpegurl") || m.contains("dash+xml")
    }

    private fun kindFromPath(uri: String): Int {
        if (isWebPage(uri)) return NativeReceiver.MEDIA_WEB
        val ext = fileExt(uri)
        return when {
            ext in imageExt -> NativeReceiver.MEDIA_IMAGE
            ext in audioExt -> NativeReceiver.MEDIA_AUDIO
            else -> NativeReceiver.MEDIA_VIDEO
        }
    }

    private fun fileExt(pathOrUrl: String): String {
        val path = pathOrUrl.substringBefore('?').substringBefore('#').lowercase()
        val dot = path.lastIndexOf('.')
        if (dot < 0) return ""
        return path.substring(dot + 1)
    }
}
