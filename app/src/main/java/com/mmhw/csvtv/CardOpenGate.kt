package com.mmhw.csvtv

/**
 * Dedupes browse-card opens while URL resolution or fragment commit is in flight.
 * A second click on the same URL is ignored. A click on a different URL replaces
 * the in-flight open so only one destination is committed.
 */
class CardOpenGate {
    var openingUrl: String? = null
        private set
    var generation: Int = 0
        private set

    sealed class Begin {
        data class Started(val generation: Int, val previousUrl: String?) : Begin()
        object AlreadyOpening : Begin()
    }

    fun tryBegin(url: String): Begin {
        val trimmed = url.trim()
        if (openingUrl != null && openingUrl.equals(trimmed, ignoreCase = true)) {
            return Begin.AlreadyOpening
        }
        val previous = openingUrl
        openingUrl = trimmed
        generation++
        return Begin.Started(generation, previous)
    }

    fun isCurrent(url: String, gen: Int): Boolean {
        return generation == gen && openingUrl.equals(url.trim(), ignoreCase = true)
    }

    fun clear() {
        openingUrl = null
    }
}
