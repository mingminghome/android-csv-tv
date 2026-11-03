package com.mmhw.csvtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDialogLayoutTest {
    @Test
    fun widthFitsNarrowPhone() {
        val w = UpdateDialogLayout.widthPx(screenWidthPx = 480, density = 1.5f)
        assertTrue(w <= 480 - 16)
        assertTrue(w > 0)
    }

    @Test
    fun widthDoesNotForce520PxOnSmallScreen() {
        val w = UpdateDialogLayout.widthPx(screenWidthPx = 400, density = 1f)
        assertTrue(w < 400)
        assertTrue(w <= 400 - 16)
    }

    @Test
    fun widthCapsOnWideTv() {
        val w = UpdateDialogLayout.widthPx(screenWidthPx = 1920, density = 2f)
        assertEquals((640 * 2f).toInt(), w)
    }

    @Test
    fun heightLeavesScreenInset() {
        val h = UpdateDialogLayout.maxHeightPx(screenHeightPx = 1080, density = 2.75f)
        assertTrue(h < 1080)
        assertTrue(h > 800)
    }

    @Test
    fun notesShrinkWhenDialogIsShort() {
        val notes = UpdateDialogLayout.notesHeightPx(
            dialogHeightPx = 500,
            chromePx = 400,
            minNotesPx = 80,
            maxNotesPx = 180
        )
        assertEquals(100, notes)
    }

    @Test
    fun notesYieldToButtonsWhenScreenIsShort() {
        val notes = UpdateDialogLayout.notesHeightPx(
            dialogHeightPx = 300,
            chromePx = 280,
            minNotesPx = 80,
            maxNotesPx = 180
        )
        assertEquals(20, notes)
    }

    @Test
    fun notesCapAtPreferredHeight() {
        val notes = UpdateDialogLayout.notesHeightPx(
            dialogHeightPx = 1080,
            chromePx = 400,
            minNotesPx = 80,
            maxNotesPx = 180
        )
        assertEquals(180, notes)
    }
}
