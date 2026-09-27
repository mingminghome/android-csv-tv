package com.mmhw.csvtv

import com.opencsv.CSVReader
import java.io.StringReader

/**
 * CSV catalog helpers: parse rows, interpret the optional `fav` column, and pick
 * which videos appear as posters on the Android TV home screen.
 */
object VideoCatalog {
    data class ParseResult(
        val videos: List<Video>,
        val error: String? = null
    )

    /**
     * Any non-blank cell (`true`, `1`, `x`, …) marks the row as a favorite.
     * Empty / missing cells are not favorites.
     */
    fun isFavCell(raw: String?): Boolean = !raw.isNullOrBlank()

    fun isUtilityCard(video: Video): Boolean {
        val url = video.url.trim().lowercase()
        return url == "settings" || url == "refresh" || url == "browser" || url == "update"
    }

    /**
     * Home-screen lead item:
     * 1. First playable row with [Video.isFav]
     * 2. Otherwise the first playable fetched row
     * 3. Null when the list is missing, empty, or only utility cards
     */
    fun pickFeatured(videos: List<Video>?): Video? {
        if (videos.isNullOrEmpty()) return null
        val playable = videos.filter { video ->
            !isUtilityCard(video) && video.title.isNotBlank() && video.url.isNotBlank()
        }
        if (playable.isEmpty()) return null
        return playable.firstOrNull { it.isFav } ?: playable.first()
    }

    /**
     * Ordered list for the Android TV home channel: featured first, then other
     * playable rows that have a thumbnail. Empty/null catalogs produce no cards.
     */
    fun homePrograms(videos: List<Video>?, limit: Int = DEFAULT_HOME_PROGRAM_LIMIT): List<Video> {
        if (videos.isNullOrEmpty() || limit <= 0) return emptyList()
        val featured = pickFeatured(videos) ?: return emptyList()
        val rest = videos.filter { video ->
            !isUtilityCard(video) &&
                video.url != featured.url &&
                video.title.isNotBlank() &&
                video.url.isNotBlank() &&
                !video.thumbnailUrl.isNullOrBlank()
        }
        return (listOf(featured) + rest).take(limit)
    }

    const val DEFAULT_HOME_PROGRAM_LIMIT = 20

    fun parseCsv(csvData: String): ParseResult {
        val videos = mutableListOf<Video>()
        try {
            val csvReader = CSVReader(StringReader(csvData))
            val rawHeaders = csvReader.readNext()
            if (rawHeaders == null) {
                return ParseResult(emptyList(), "Invalid CSV format: Empty file")
            }
            val headers = rawHeaders.map { it.trim().trim('\uFEFF') }.toTypedArray()

            val titleIndex = columnIndex(headers, "title")
            val urlIndex = columnIndex(headers, "url")
            val thumbnailUrlIndex = columnIndex(headers, "thumbnailUrl")
            val groupNameIndex = columnIndex(headers, "groupName")
            val favIndex = columnIndex(headers, "fav", "favorite")

            if (titleIndex == -1 || urlIndex == -1 || groupNameIndex == -1) {
                return ParseResult(emptyList(), "Invalid CSV format: Missing required columns")
            }

            var row: Array<String>?
            while (csvReader.readNext().also { row = it } != null) {
                val cells = row ?: continue
                val title = cell(cells, titleIndex)
                val url = cell(cells, urlIndex)
                val thumbnailUrl = cell(cells, thumbnailUrlIndex).takeIf { it.isNotBlank() }
                val groupName = cell(cells, groupNameIndex).ifBlank { "Default" }
                val isFav = isFavCell(cell(cells, favIndex))

                if (title.isNotBlank() && url.isNotBlank()) {
                    videos.add(Video(title, url, thumbnailUrl, groupName, isFav))
                }
            }
            return ParseResult(videos, null)
        } catch (e: Exception) {
            return ParseResult(emptyList(), "Error parsing CSV: ${e.message}")
        }
    }

    internal fun columnIndex(headers: Array<String>, vararg names: String): Int {
        for (name in names) {
            val i = headers.indexOfFirst { it.trim().equals(name, ignoreCase = true) }
            if (i >= 0) return i
        }
        return -1
    }

    private fun cell(row: Array<String>, index: Int): String {
        if (index < 0 || index >= row.size) return ""
        return row[index].trim()
    }
}
