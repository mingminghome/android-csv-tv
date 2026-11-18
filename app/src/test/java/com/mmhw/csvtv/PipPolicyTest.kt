package com.mmhw.csvtv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipPolicyTest {
    @Test
    fun menuOnPlayerOpensMinimizeAndActivatesIt() {
        val open = PipPolicy.onKey(stagePlayer(), KeyEvent.KEYCODE_MENU, longBack = false)
        assertEquals(PipPolicy.Effect.OPEN_BAR, open.effect)
        assertEquals(listOf(PipPolicy.Action.MINIMIZE), PipPolicy.actions(open.model))

        val go = PipPolicy.onKey(open.model, KeyEvent.KEYCODE_DPAD_CENTER, longBack = false)
        assertEquals(PipPolicy.Effect.MINIMIZE, go.effect)
    }

    @Test
    fun closeIsOnTheBarWheneverACornerExists() {
        val parked = PipPolicy.afterMinimize(stagePlayer(), PipPolicy.Kind.NONE)
        assertEquals(PipPolicy.Kind.PLAYER, parked.corner)
        val actions = PipPolicy.actions(parked)
        assertTrue(actions.contains(PipPolicy.Action.CLOSE))
        assertFalse(actions.contains(PipPolicy.Action.MINIMIZE))

        val bar = PipPolicy.onKey(parked, KeyEvent.KEYCODE_MENU, longBack = false)
        val closeIndex = PipPolicy.actions(bar.model).indexOf(PipPolicy.Action.CLOSE)
        var model = bar.model.copy(barIndex = closeIndex)
        val closed = PipPolicy.onKey(model, KeyEvent.KEYCODE_DPAD_CENTER, longBack = false)
        assertEquals(PipPolicy.Effect.CLOSE_CORNER, closed.effect)
        assertEquals(PipPolicy.Kind.NONE, closed.model.corner)
        assertEquals(PipPolicy.Focus.STAGE, closed.model.focus)
        assertFalse(closed.model.barOpen)
    }

    @Test
    fun heldBackOnTheCornerClosesShortBackReturnsToTheStage() {
        val corner = PipPolicy.afterMinimize(stagePlayer(), PipPolicy.Kind.NONE)
            .copy(focus = PipPolicy.Focus.CORNER)
        val back = PipPolicy.onKey(corner, KeyEvent.KEYCODE_BACK, longBack = false)
        assertEquals(PipPolicy.Effect.FOCUS_STAGE, back.effect)
        assertEquals(PipPolicy.Kind.PLAYER, back.model.corner)

        val held = PipPolicy.onKey(corner, KeyEvent.KEYCODE_BACK, longBack = true)
        assertEquals(PipPolicy.Effect.CLOSE_CORNER, held.effect)
        assertEquals(PipPolicy.Kind.NONE, held.model.corner)
    }

    @Test
    fun cornerKeysMoveSizeAndSwapWithoutTouchingTheStageDpad() {
        val corner = PipPolicy.afterMinimize(stagePlayer(), PipPolicy.Kind.WEB)
            .copy(focus = PipPolicy.Focus.CORNER)
        val moved = PipPolicy.onKey(corner, KeyEvent.KEYCODE_DPAD_RIGHT, longBack = false)
        assertEquals(PipPolicy.Effect.CYCLE_PLACE, moved.effect)
        assertEquals(PipPolicy.Place.BOTTOM_LEFT, moved.model.place)

        val sized = PipPolicy.onKey(moved.model, KeyEvent.KEYCODE_DPAD_UP, longBack = false)
        assertEquals(PipPolicy.Effect.TOGGLE_SIZE, sized.effect)
        assertEquals(PipPolicy.Size.MEDIUM, sized.model.size)

        val swapped = PipPolicy.onKey(sized.model, KeyEvent.KEYCODE_DPAD_CENTER, longBack = false)
        assertEquals(PipPolicy.Effect.SWAP, swapped.effect)
        assertEquals(PipPolicy.Kind.PLAYER, swapped.model.stage)
        assertEquals(PipPolicy.Kind.WEB, swapped.model.corner)
        assertFalse(swapped.model.soundOnCorner)
    }

    @Test
    fun audioFlipDoesNotMovePicturesAndSwapGivesSoundBackToTheStage() {
        val parked = PipPolicy.afterMinimize(stagePlayer(), PipPolicy.Kind.WEB)
        val bar = PipPolicy.onKey(parked, KeyEvent.KEYCODE_MENU, longBack = false).model
        val audioIndex = PipPolicy.actions(bar).indexOf(PipPolicy.Action.AUDIO)
        val flipped = PipPolicy.onKey(bar.copy(barIndex = audioIndex), KeyEvent.KEYCODE_DPAD_CENTER, longBack = false)
        assertEquals(PipPolicy.Effect.TOGGLE_AUDIO, flipped.effect)
        assertTrue(flipped.model.soundOnCorner)
        assertEquals(parked.stage, flipped.model.stage)
        assertEquals(parked.corner, flipped.model.corner)

        val swapped = PipPolicy.afterSwap(flipped.model)
        assertFalse(swapped.soundOnCorner)
        assertEquals(PipPolicy.Kind.PLAYER, swapped.stage)
    }

    @Test
    fun minimizeToBrowseKeepsSoundOnTheCorner() {
        val parked = PipPolicy.afterMinimize(stagePlayer(), PipPolicy.Kind.NONE)
        assertTrue(parked.soundOnCorner)
        val besidePage = PipPolicy.afterMinimize(stagePlayer(), PipPolicy.Kind.WEB)
        assertFalse(besidePage.soundOnCorner)
    }

    @Test
    fun expandFromBrowseClearsTheCorner() {
        val parked = PipPolicy.afterMinimize(stagePlayer(), PipPolicy.Kind.NONE)
        val expanded = PipPolicy.afterSwap(parked)
        assertEquals(PipPolicy.Kind.PLAYER, expanded.stage)
        assertEquals(PipPolicy.Kind.NONE, expanded.corner)
    }

    @Test
    fun browseAloneDoesNotTakeMenu() {
        val step = PipPolicy.onKey(PipPolicy.Model(), KeyEvent.KEYCODE_MENU, longBack = false)
        assertEquals(PipPolicy.Effect.FALL_THROUGH, step.effect)
    }

    @Test
    fun keyboardPMatchesMenuAndEscapeMatchesBack() {
        val open = PipPolicy.onKey(stagePlayer(), KeyEvent.KEYCODE_P, longBack = false)
        assertEquals(PipPolicy.Effect.OPEN_BAR, open.effect)
        val closed = PipPolicy.onKey(open.model, KeyEvent.KEYCODE_P, longBack = false)
        assertEquals(PipPolicy.Effect.CLOSE_BAR, closed.effect)

        val parked = PipPolicy.afterMinimize(stagePlayer(), PipPolicy.Kind.NONE)
            .copy(focus = PipPolicy.Focus.CORNER)
        val back = PipPolicy.onKey(parked, KeyEvent.KEYCODE_ESCAPE, longBack = false)
        assertEquals(PipPolicy.Effect.FOCUS_STAGE, back.effect)
        assertEquals(PipPolicy.Kind.PLAYER, back.model.corner)

        val browsing = PipPolicy.onKey(PipPolicy.Model(), KeyEvent.KEYCODE_P, longBack = false)
        assertEquals(PipPolicy.Effect.FALL_THROUGH, browsing.effect)
    }

    @Test
    fun stageDpadIsLeftAlone() {
        val parked = PipPolicy.afterMinimize(stagePlayer(), PipPolicy.Kind.NONE)
        val step = PipPolicy.onKey(parked, KeyEvent.KEYCODE_DPAD_RIGHT, longBack = false)
        assertEquals(PipPolicy.Effect.FALL_THROUGH, step.effect)
    }

    @Test
    fun secondPlayerOnALowRamDeviceCapsTheCorner() {
        assertTrue(
            PipPolicy.capCornerDecoder(
                lowRam = true,
                stage = PipPolicy.Kind.PLAYER,
                corner = PipPolicy.Kind.PLAYER
            )
        )
        assertFalse(
            PipPolicy.capCornerDecoder(
                lowRam = false,
                stage = PipPolicy.Kind.PLAYER,
                corner = PipPolicy.Kind.PLAYER
            )
        )
        assertFalse(
            PipPolicy.capCornerDecoder(
                lowRam = true,
                stage = PipPolicy.Kind.PLAYER,
                corner = PipPolicy.Kind.WEB
            )
        )
    }

    @Test
    fun openingWhileAVideoIsOnStageAsksReplaceOrCorner() {
        val choice = PipPolicy.beginChoice(stagePlayer())
        assertEquals(listOf(PipPolicy.Action.REPLACE, PipPolicy.Action.PLAY_CORNER), PipPolicy.actions(choice))
        assertEquals(0, choice.barIndex)

        val replace = PipPolicy.onKey(choice, KeyEvent.KEYCODE_DPAD_CENTER, longBack = false)
        assertEquals(PipPolicy.Effect.REPLACE_STAGE, replace.effect)
        assertFalse(replace.model.pendingChoice)
        assertEquals(PipPolicy.Kind.PLAYER, replace.model.stage)
        assertEquals(PipPolicy.Kind.NONE, replace.model.corner)

        val again = PipPolicy.beginChoice(stagePlayer())
        val moved = PipPolicy.onKey(again, KeyEvent.KEYCODE_DPAD_RIGHT, longBack = false)
        val corner = PipPolicy.onKey(moved.model, KeyEvent.KEYCODE_DPAD_CENTER, longBack = false)
        assertEquals(PipPolicy.Effect.PLAY_IN_CORNER, corner.effect)
        assertFalse(corner.model.pendingChoice)

        val cancelled = PipPolicy.onKey(again, KeyEvent.KEYCODE_BACK, longBack = false)
        assertEquals(PipPolicy.Effect.CANCEL_CHOICE, cancelled.effect)
        assertFalse(cancelled.model.pendingChoice)
        assertFalse(cancelled.model.barOpen)
    }

    @Test
    fun minimizeRefusesASecondCorner() {
        val full = PipPolicy.Model(stage = PipPolicy.Kind.PLAYER, corner = PipPolicy.Kind.WEB)
        assertEquals(full, PipPolicy.afterMinimize(full, PipPolicy.Kind.NONE))
        assertFalse(PipPolicy.actions(full).contains(PipPolicy.Action.MINIMIZE))
        assertTrue(PipPolicy.actions(full).contains(PipPolicy.Action.CLOSE))
    }

    @Test
    fun sameCatalogClickExpandsTheCornerAndLeavesTheStage() {
        val click = "https://Example.com/live"
        assertEquals(
            PipPolicy.Slot.CORNER,
            PipPolicy.slotFor(emptyList(), listOf("https://example.com/live"), click)
        )
        assertEquals(
            PipPolicy.Slot.STAGE,
            PipPolicy.slotFor(listOf(click), emptyList(), click)
        )
        assertEquals(
            PipPolicy.Slot.CORNER,
            PipPolicy.slotFor(listOf(click), listOf(click), click)
        )
        assertEquals(
            PipPolicy.Slot.NONE,
            PipPolicy.slotFor(listOf("https://example.com/other"), emptyList(), click)
        )
    }

    @Test
    fun selectRevealedFragmentReturnsTopmostHiddenFragment() {
        data class FakeFrag(val name: String, val hidden: Boolean, val added: Boolean = true)

        val main = FakeFrag("main", hidden = true)
        val web = FakeFrag("web", hidden = true)
        val player = FakeFrag("player", hidden = false)

        // Native player opened from webview: leaving player should restore webview, not main browse
        val restoredFromWeb = PipPolicy.selectRevealedFragment(
            fragments = listOf(main, web, player),
            skip = player,
            corner = null,
            isHidden = { it.hidden },
            isAdded = { it.added },
            fallback = main
        )
        assertEquals("web", restoredFromWeb?.name)

        // Leaving webview restores main browse
        val restoredFromMain = PipPolicy.selectRevealedFragment(
            fragments = listOf(main, web),
            skip = web,
            corner = null,
            isHidden = { it.hidden },
            isAdded = { it.added },
            fallback = main
        )
        assertEquals("main", restoredFromMain?.name)

        // Native player opened directly from main: leaving player restores main browse
        val directPlayer = PipPolicy.selectRevealedFragment(
            fragments = listOf(main, player),
            skip = player,
            corner = null,
            isHidden = { it.hidden },
            isAdded = { it.added },
            fallback = main
        )
        assertEquals("main", directPlayer?.name)

        // Corner window is never selected as stage reveal
        val corner = FakeFrag("corner", hidden = true)
        val withCorner = PipPolicy.selectRevealedFragment(
            fragments = listOf(main, corner, player),
            skip = player,
            corner = corner,
            isHidden = { it.hidden },
            isAdded = { it.added },
            fallback = main
        )
        assertEquals("main", withCorner?.name)
    }

    private fun stagePlayer() = PipPolicy.Model(stage = PipPolicy.Kind.PLAYER)
}
