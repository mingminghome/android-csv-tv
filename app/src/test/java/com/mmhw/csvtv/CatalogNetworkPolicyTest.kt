package com.mmhw.csvtv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogNetworkPolicyTest {

    @Test
    fun utilityCardsAreSkipped() {
        assertTrue(CatalogNetworkPolicy.shouldSkipPrecheck(Video("Settings", "settings", null)))
        assertTrue(CatalogNetworkPolicy.shouldSkipPrecheck(Video("Browser", "browser", null)))
        assertTrue(CatalogNetworkPolicy.shouldSkipPrecheck(Video("Refresh", "Refresh", null)))
    }

    @Test
    fun alreadyResolvedCardsAreSkipped() {
        val known = Video("Live", "https://cdn.example/a.m3u8", null).apply { isValid = true }
        assertTrue(CatalogNetworkPolicy.shouldSkipPrecheck(known))
    }

    @Test
    fun unknownStreamIsProbed() {
        val unknown = Video("Live", "https://cdn.example/a.m3u8", null)
        assertFalse(CatalogNetworkPolicy.shouldSkipPrecheck(unknown))
    }
}
