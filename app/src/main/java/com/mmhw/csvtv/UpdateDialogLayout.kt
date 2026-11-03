package com.mmhw.csvtv

/**
 * Sizes the in-app update dialog so it stays on-screen on phones.
 * A fixed 520px minimum is wider than a small display and clips the actions.
 */
object UpdateDialogLayout {
    fun widthPx(screenWidthPx: Int, density: Float): Int {
        val inset = insetPx(density)
        val preferred = (640f * density).toInt()
        val available = (screenWidthPx - inset * 2).coerceAtLeast(0)
        return available.coerceAtMost(preferred)
    }

    fun maxHeightPx(screenHeightPx: Int, density: Float): Int {
        val inset = insetPx(density)
        return (screenHeightPx - inset * 2).coerceAtLeast(0)
    }

    fun notesHeightPx(
        dialogHeightPx: Int,
        chromePx: Int,
        minNotesPx: Int,
        maxNotesPx: Int
    ): Int {
        val available = (dialogHeightPx - chromePx).coerceAtLeast(0)
        if (available <= minNotesPx) return available
        val low = minOf(minNotesPx, maxNotesPx)
        val high = maxOf(minNotesPx, maxNotesPx)
        return available.coerceIn(low, high)
    }

    private fun insetPx(density: Float): Int = (16f * density).toInt().coerceAtLeast(8)
}
