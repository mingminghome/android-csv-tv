package com.mmhw.csvtv

import android.view.KeyEvent

/**
 * Remote and keyboard rules for the in-app corner window.
 *
 * The stage keeps D-pad and, unless Audio was flipped, the sound.
 * Menu opens a bar. The P key does the same thing, because a computer
 * keyboard has no Menu key. Close is on that bar, and a held Back on the
 * corner closes it too. A short Back on the corner only returns focus to
 * the stage. Escape follows Back.
 */
object PipPolicy {
    enum class Kind { NONE, WEB, PLAYER }

    enum class Place { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_LEFT, TOP_RIGHT }

    enum class Size { MINI, MEDIUM }

    enum class Focus { STAGE, BAR, CORNER }

    /** Where a catalog click already lives. Corner wins, so the click expands it. */
    enum class Slot { NONE, STAGE, CORNER }

    enum class Action { MINIMIZE, SWAP, AUDIO, SIZE, CLOSE, REPLACE, PLAY_CORNER }

    enum class Effect {
        FALL_THROUGH,
        OPEN_BAR,
        CLOSE_BAR,
        FOCUS_CORNER,
        FOCUS_STAGE,
        MOVE_BAR,
        MINIMIZE,
        SWAP,
        TOGGLE_AUDIO,
        CYCLE_PLACE,
        TOGGLE_SIZE,
        CLOSE_CORNER,
        REPLACE_STAGE,
        PLAY_IN_CORNER,
        CANCEL_CHOICE
    }

    data class Model(
        val stage: Kind = Kind.NONE,
        val corner: Kind = Kind.NONE,
        val place: Place = Place.BOTTOM_RIGHT,
        val size: Size = Size.MINI,
        val soundOnCorner: Boolean = false,
        val focus: Focus = Focus.STAGE,
        val barOpen: Boolean = false,
        val barIndex: Int = 0,
        /** A second channel was opened while one is already on the stage. */
        val pendingChoice: Boolean = false
    )

    data class Step(val model: Model, val effect: Effect)

    fun actions(model: Model): List<Action> {
        if (model.pendingChoice) {
            return listOf(Action.REPLACE, Action.PLAY_CORNER)
        }
        val actions = ArrayList<Action>(5)
        if (model.corner == Kind.NONE && model.stage != Kind.NONE) {
            actions.add(Action.MINIMIZE)
        }
        if (model.corner != Kind.NONE) {
            actions.add(Action.SWAP)
            actions.add(Action.AUDIO)
            actions.add(Action.SIZE)
            actions.add(Action.CLOSE)
        }
        return actions
    }

    fun sameSource(a: String, b: String): Boolean {
        return a.isNotBlank() && a.trim().equals(b.trim(), ignoreCase = true)
    }

    fun slotFor(stageUrls: List<String>, cornerUrls: List<String>, click: String): Slot {
        fun hit(urls: List<String>) = urls.any { sameSource(it, click) }
        return when {
            hit(cornerUrls) -> Slot.CORNER
            hit(stageUrls) -> Slot.STAGE
            else -> Slot.NONE
        }
    }

    /** A second full-size decode is the one we shrink, and only on low-RAM devices. */
    fun capCornerDecoder(lowRam: Boolean, stage: Kind, corner: Kind): Boolean {
        return lowRam && stage == Kind.PLAYER && corner == Kind.PLAYER
    }

    /** Stage is already media and the corner is free: ask before opening another. */
    fun beginChoice(model: Model): Model {
        if (model.stage == Kind.NONE || model.corner != Kind.NONE) return model
        return model.copy(
            pendingChoice = true,
            barOpen = true,
            barIndex = 0,
            focus = Focus.BAR
        )
    }

    fun onKey(model: Model, keyCode: Int, longBack: Boolean): Step {
        if (model.pendingChoice) {
            if (isMenuKey(keyCode) || isBackKey(keyCode)) {
                return Step(model.copy(pendingChoice = false).stageFocus(), Effect.CANCEL_CHOICE)
            }
            if (model.focus == Focus.BAR && model.barOpen) {
                return onBarKey(model, keyCode, longBack)
            }
            return Step(model, Effect.FALL_THROUGH)
        }
        if (isMenuKey(keyCode)) {
            val available = actions(model)
            if (available.isEmpty()) return Step(model, Effect.FALL_THROUGH)
            if (model.barOpen) {
                return Step(model.stageFocus(), Effect.CLOSE_BAR)
            }
            return Step(
                model.copy(barOpen = true, barIndex = 0, focus = Focus.BAR),
                Effect.OPEN_BAR
            )
        }

        if (model.focus == Focus.BAR && model.barOpen) {
            return onBarKey(model, keyCode, longBack)
        }
        if (model.focus == Focus.CORNER && model.corner != Kind.NONE) {
            return onCornerKey(model, keyCode, longBack)
        }
        return Step(model, Effect.FALL_THROUGH)
    }

    fun afterOpen(model: Model, kind: Kind): Model {
        return model.copy(
            stage = kind,
            soundOnCorner = false,
            focus = Focus.STAGE,
            barOpen = false,
            barIndex = 0,
            pendingChoice = false
        )
    }

    /** Corner becomes whatever was on stage. [revealed] is the surface now large. */
    fun afterMinimize(model: Model, revealed: Kind): Model {
        if (model.stage == Kind.NONE || model.corner != Kind.NONE) return model
        return model.copy(
            stage = revealed,
            corner = model.stage,
            // Browse has nothing to hear, so the corner keeps the sound.
            soundOnCorner = revealed == Kind.NONE,
            focus = Focus.STAGE,
            barOpen = false,
            barIndex = 0,
            pendingChoice = false
        )
    }

    fun afterSwap(model: Model): Model {
        if (model.corner == Kind.NONE) return model
        return if (model.stage == Kind.NONE) {
            model.copy(
                stage = model.corner,
                corner = Kind.NONE,
                soundOnCorner = false,
                focus = Focus.STAGE,
                barOpen = false,
                pendingChoice = false
            )
        } else {
            model.copy(
                stage = model.corner,
                corner = model.stage,
                soundOnCorner = false,
                focus = Focus.STAGE,
                barOpen = false,
                pendingChoice = false
            )
        }
    }

    fun afterAudio(model: Model): Model {
        if (model.corner == Kind.NONE) return model
        return model.copy(soundOnCorner = !model.soundOnCorner)
    }

    fun afterClose(model: Model): Model {
        return model.copy(
            corner = Kind.NONE,
            soundOnCorner = false,
            focus = Focus.STAGE,
            barOpen = false,
            barIndex = 0,
            pendingChoice = false
        )
    }

    fun label(action: Action, soundOnCorner: Boolean): String {
        return when (action) {
            Action.MINIMIZE -> "Minimize"
            Action.SWAP -> "Swap"
            Action.AUDIO -> if (soundOnCorner) "Sound: small" else "Sound: large"
            Action.SIZE -> "Size"
            Action.CLOSE -> "Close"
            Action.REPLACE -> "Replace"
            Action.PLAY_CORNER -> "Play in the corner"
        }
    }

    private fun onBarKey(model: Model, keyCode: Int, longBack: Boolean): Step {
        val items = actions(model)
        if (items.isEmpty()) return Step(model.stageFocus(), Effect.CLOSE_BAR)
        val index = model.barIndex.coerceIn(0, items.lastIndex)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val next = (index - 1).coerceAtLeast(0)
                Step(model.copy(barIndex = next), Effect.MOVE_BAR)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val next = (index + 1).coerceAtMost(items.lastIndex)
                Step(model.copy(barIndex = next), Effect.MOVE_BAR)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                activate(model, items[index])
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (model.pendingChoice) Step(model, Effect.MOVE_BAR)
                else if (model.corner == Kind.NONE) Step(model, Effect.FALL_THROUGH)
                else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    Step(
                        model.copy(focus = Focus.CORNER, barOpen = false),
                        Effect.FOCUS_CORNER
                    )
                } else {
                    Step(model, Effect.FALL_THROUGH)
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (longBack && keyCode == KeyEvent.KEYCODE_BACK && model.corner != Kind.NONE) {
                    Step(afterClose(model), Effect.CLOSE_CORNER)
                } else {
                    Step(model.stageFocus(), Effect.CLOSE_BAR)
                }
            }
            else -> Step(model, Effect.FALL_THROUGH)
        }
    }

    private fun onCornerKey(model: Model, keyCode: Int, longBack: Boolean): Step {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                Step(afterSwap(model), Effect.SWAP)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                Step(model.copy(place = nextPlace(model.place, keyCode)), Effect.CYCLE_PLACE)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val size = if (model.size == Size.MINI) Size.MEDIUM else Size.MINI
                Step(model.copy(size = size), Effect.TOGGLE_SIZE)
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (longBack && keyCode == KeyEvent.KEYCODE_BACK) {
                    Step(afterClose(model), Effect.CLOSE_CORNER)
                } else {
                    Step(model.stageFocus(), Effect.FOCUS_STAGE)
                }
            }
            else -> Step(model, Effect.FALL_THROUGH)
        }
    }

    fun activate(model: Model, action: Action): Step {
        return when (action) {
            Action.MINIMIZE -> Step(model, Effect.MINIMIZE)
            Action.SWAP -> Step(afterSwap(model), Effect.SWAP)
            Action.AUDIO -> Step(afterAudio(model), Effect.TOGGLE_AUDIO)
            Action.SIZE -> {
                val size = if (model.size == Size.MINI) Size.MEDIUM else Size.MINI
                Step(model.copy(size = size, barOpen = false, focus = Focus.STAGE), Effect.TOGGLE_SIZE)
            }
            Action.CLOSE -> Step(afterClose(model), Effect.CLOSE_CORNER)
            Action.REPLACE -> Step(model.choiceDone(), Effect.REPLACE_STAGE)
            Action.PLAY_CORNER -> Step(model.choiceDone(), Effect.PLAY_IN_CORNER)
        }
    }

    /** Menu on a remote. P on a keyboard. */
    private fun isMenuKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_P
    }

    private fun isBackKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
    }

    private fun nextPlace(place: Place, keyCode: Int): Place {
        val order = Place.entries
        val index = order.indexOf(place)
        val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
        return order[(index + delta + order.size) % order.size]
    }

    private fun Model.stageFocus(): Model = copy(barOpen = false, focus = Focus.STAGE, barIndex = 0)

    private fun Model.choiceDone(): Model = copy(
        pendingChoice = false,
        barOpen = false,
        focus = Focus.STAGE,
        barIndex = 0
    )
}
