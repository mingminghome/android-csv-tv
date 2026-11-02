package com.mmhw.csvtv.receiver

import org.json.JSONArray
import org.json.JSONObject

/** Playlist of photos and videos shared as a collection. */
data class Album(
    val title: String,
    val intervalMs: Long,
    val loop: Boolean,
    val items: List<Item>
) {
    data class Item(
        val url: String,
        val mime: String,
        val title: String,
        val kind: String
    ) {
        val isImage: Boolean
            get() = kind == "image" || mime.startsWith("image/")
    }

    fun toJson(itemUrls: List<String>): String {
        val arr = JSONArray()
        items.forEachIndexed { i, item ->
            val url = itemUrls.getOrNull(i) ?: item.url
            arr.put(
                JSONObject()
                    .put("url", url)
                    .put("mime", item.mime)
                    .put("title", item.title)
                    .put("kind", item.kind)
            )
        }
        return JSONObject()
            .put("title", title)
            .put("intervalMs", intervalMs)
            .put("loop", loop)
            .put("items", arr)
            .toString()
    }

    companion object {
        const val MIME = "text/x-csvtv-album"
        const val DEFAULT_INTERVAL_MS = 7_000L
        const val MAX_ITEMS = 80

        fun isAlbum(uri: String, mime: String?): Boolean {
            val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (m == MIME || m == "text/x-castify-album" ||
                m == "application/vnd.csvtv.album+json"
            ) {
                return true
            }
            val path = uri.substringBefore('?').substringBefore('#').lowercase()
            return path.endsWith("/album.json") || path.endsWith(".album.json")
        }

        fun parse(json: String): Album {
            val o = JSONObject(json)
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val url = it.optString("url")
                if (url.isBlank()) continue
                val mime = it.optString("mime").ifBlank { "image/jpeg" }
                val kind = it.optString("kind").ifBlank {
                    if (mime.startsWith("image/")) "image" else "video"
                }
                items.add(
                    Item(
                        url = url,
                        mime = mime,
                        title = it.optString("title").ifBlank { "Media" },
                        kind = kind
                    )
                )
            }
            return Album(
                title = o.optString("title").ifBlank { "Collection" },
                intervalMs = o.optLong("intervalMs", DEFAULT_INTERVAL_MS).coerceIn(2_000L, 60_000L),
                loop = o.optBoolean("loop", true),
                items = items
            )
        }

        fun kindOf(mime: String): String {
            val m = mime.lowercase()
            return when {
                m.startsWith("image/") -> "image"
                m.startsWith("audio/") -> "audio"
                else -> "video"
            }
        }
    }
}
