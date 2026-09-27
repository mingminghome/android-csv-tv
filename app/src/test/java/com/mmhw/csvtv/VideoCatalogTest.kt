package com.mmhw.csvtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCatalogTest {

    @Test
    fun favCell_anyNonBlankIsFavorite() {
        assertTrue(VideoCatalog.isFavCell("true"))
        assertTrue(VideoCatalog.isFavCell("TRUE"))
        assertTrue(VideoCatalog.isFavCell("1"))
        assertTrue(VideoCatalog.isFavCell("x"))
        assertTrue(VideoCatalog.isFavCell("*"))
        assertFalse(VideoCatalog.isFavCell(null))
        assertFalse(VideoCatalog.isFavCell(""))
        assertFalse(VideoCatalog.isFavCell("   "))
    }

    @Test
    fun parseCsv_readsFavAndThumbnail() {
        val csv = """
            groupName,title,url,thumbnailUrl,fav
            Movies,Fav One,https://example.com/a.m3u8,https://img.example/a.jpg,true
            Movies,Second,https://example.com/b.m3u8,https://img.example/b.jpg,
            Live,Third,https://example.com/c.m3u8,,x
        """.trimIndent()

        val result = VideoCatalog.parseCsv(csv)
        assertNull(result.error)
        assertEquals(3, result.videos.size)
        assertTrue(result.videos[0].isFav)
        assertFalse(result.videos[1].isFav)
        assertTrue(result.videos[2].isFav)
        assertEquals("https://img.example/a.jpg", result.videos[0].thumbnailUrl)
        assertNull(result.videos[2].thumbnailUrl)
    }

    @Test
    fun parseCsv_favoriteAliasHeader() {
        val csv = """
            groupName,title,url,thumbnailUrl,favorite
            Movies,One,https://example.com/a.m3u8,https://img.example/a.jpg,1
        """.trimIndent()
        val result = VideoCatalog.parseCsv(csv)
        assertTrue(result.videos.single().isFav)
    }

    @Test
    fun parseCsv_emptyFile_returnsError() {
        val result = VideoCatalog.parseCsv("")
        assertTrue(result.videos.isEmpty())
        assertEquals("Invalid CSV format: Empty file", result.error)
    }

    @Test
    fun parseCsv_headersOnly_returnsEmptyListWithoutError() {
        val result = VideoCatalog.parseCsv("groupName,title,url,thumbnailUrl,fav\n")
        assertTrue(result.videos.isEmpty())
        assertNull(result.error)
    }

    @Test
    fun pickFeatured_prefersFirstFavThenFirstRow() {
        val first = video("First", "https://a", thumb = "https://img/a.jpg")
        val fav = video("Fav", "https://b", thumb = "https://img/b.jpg", fav = true)
        val third = video("Third", "https://c", thumb = "https://img/c.jpg")

        assertEquals(fav, VideoCatalog.pickFeatured(listOf(first, fav, third)))
        assertEquals(first, VideoCatalog.pickFeatured(listOf(first, third)))
    }

    @Test
    fun pickFeatured_emptyOrUtilityOnly_returnsNull() {
        assertNull(VideoCatalog.pickFeatured(null))
        assertNull(VideoCatalog.pickFeatured(emptyList()))
        assertNull(
            VideoCatalog.pickFeatured(
                listOf(Video("Settings", "Settings", null), Video("Browser", "browser", null))
            )
        )
    }

    @Test
    fun homePrograms_featuredFirst_skipsUtilityAndThumbnaillessRest() {
        val first = video("First", "https://a", thumb = null)
        val fav = video("Fav", "https://b", thumb = "https://img/b.jpg", fav = true)
        val noThumb = video("NoThumb", "https://c", thumb = null)
        val withThumb = video("Art", "https://d", thumb = "https://img/d.jpg")
        val settings = Video("Settings", "Settings", null)

        val programs = VideoCatalog.homePrograms(
            listOf(first, fav, noThumb, withThumb, settings)
        )
        assertEquals(listOf("Fav", "Art"), programs.map { it.title })
    }

    @Test
    fun homePrograms_noFav_usesFirstFetchedRow() {
        val first = video("First", "https://a", thumb = "https://img/a.jpg")
        val second = video("Second", "https://b", thumb = "https://img/b.jpg")
        val programs = VideoCatalog.homePrograms(listOf(first, second))
        assertEquals("First", programs.first().title)
        assertEquals(2, programs.size)
    }

    @Test
    fun homePrograms_firstRowWithoutThumb_stillLeadCard() {
        val first = video("First", "https://a", thumb = null)
        val second = video("Second", "https://b", thumb = "https://img/b.jpg")
        val programs = VideoCatalog.homePrograms(listOf(first, second))
        assertEquals(listOf("First", "Second"), programs.map { it.title })
    }

    @Test
    fun homePrograms_emptyCatalog_isEmpty() {
        assertTrue(VideoCatalog.homePrograms(null).isEmpty())
        assertTrue(VideoCatalog.homePrograms(emptyList()).isEmpty())
        assertTrue(VideoCatalog.homePrograms(listOf(Video("Refresh", "Refresh", null))).isEmpty())
    }

    @Test
    fun homePrograms_respectsLimit() {
        val videos = (1..10).map { i ->
            video("V$i", "https://example.com/$i", thumb = "https://img/$i.jpg", fav = i == 7)
        }
        val programs = VideoCatalog.homePrograms(videos, limit = 3)
        assertEquals(3, programs.size)
        assertEquals("V7", programs.first().title)
    }

    private fun video(
        title: String,
        url: String,
        thumb: String?,
        fav: Boolean = false,
        group: String = "Movies"
    ): Video {
        return Video(title, url, thumb, group, fav)
    }
}
