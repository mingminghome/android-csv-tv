package com.mmhw.csvtv

/**
 * D-pad pointer, toolbar chrome, and Center-click routing for the TV WebView.
 *
 * Close/Back live in the overlay toolbar; page clicks go to the WebView. Mixing
 * those hosts is what made the cursor miss chrome or steal verification input.
 */
object WebViewInputPolicy {

    const val TOOLBAR_HEIGHT_DP = 56f
    const val TOOLBAR_SHOW_Y_DP = 15f
    const val TOOLBAR_HIDE_Y_DP = 80f
    const val TOOLBAR_LEAVE_Y_DP = 66f
    const val EDGE_PAN_DP = 40f
    const val POINTER_SPEED_PX = 20f
    const val POINTER_SIZE_PX = 36
    const val POINTER_HIDE_MS = 3000L
    const val DOUBLE_CLICK_MS = 400L
    const val SPEED_ACCEL_AFTER_MS = 300L
    const val SPEED_ACCEL_RAMP_MS = 1500L
    const val SPEED_MAX_MULTIPLIER = 4f

    enum class CenterAction {
        OPEN_URL_BAR,
        ACTIVATE_TOOLBAR,
        DEFER_PAGE_OR_FULLSCREEN
    }

    enum class ClickDispatch {
        CONTAINER,
        POINTER_HOST
    }

    enum class DpadDir { UP, DOWN, LEFT, RIGHT }

    fun centerAction(
        urlBarFocused: Boolean,
        toolbarShowing: Boolean,
        toolbarItemFocused: Boolean,
        inFullscreen: Boolean
    ): CenterAction {
        if (urlBarFocused) return CenterAction.OPEN_URL_BAR
        if (!inFullscreen && toolbarShowing && toolbarItemFocused) {
            return CenterAction.ACTIVATE_TOOLBAR
        }
        return CenterAction.DEFER_PAGE_OR_FULLSCREEN
    }

    fun canActivateToolbarItem(
        inFullscreen: Boolean,
        toolbarShowing: Boolean,
        hasFocusedItem: Boolean
    ): Boolean {
        return !inFullscreen && toolbarShowing && hasFocusedItem
    }

    fun isPointerOverToolbar(
        pointerY: Float,
        toolbarHeightPx: Float,
        toolbarShowing: Boolean,
        inFullscreen: Boolean
    ): Boolean {
        return !inFullscreen && toolbarShowing && pointerY < toolbarHeightPx
    }

    fun clickDispatch(overToolbar: Boolean, containerReady: Boolean): ClickDispatch {
        return if (overToolbar && containerReady) ClickDispatch.CONTAINER else ClickDispatch.POINTER_HOST
    }

    fun shouldInjectJsClick(inFullscreen: Boolean, onChallengePage: Boolean): Boolean {
        return !inFullscreen && !onChallengePage
    }

    fun shouldOfferIme(onChallengePage: Boolean): Boolean = !onChallengePage

    fun clampToHost(x: Float, y: Float, hostWidth: Int, hostHeight: Int): Pair<Float, Float> {
        val maxX = (hostWidth - 1).coerceAtLeast(0).toFloat()
        val maxY = (hostHeight - 1).coerceAtLeast(0).toFloat()
        return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
    }

    fun overlayTranslation(
        pointerX: Float,
        pointerY: Float,
        pointerWidth: Int,
        pointerHeight: Int,
        hostScreenX: Int,
        hostScreenY: Int,
        overlayScreenX: Int,
        overlayScreenY: Int
    ): Pair<Float, Float> {
        return (pointerX + hostScreenX - overlayScreenX - pointerWidth / 2f) to
            (pointerY + hostScreenY - overlayScreenY - pointerHeight / 2f)
    }

    fun pointerSpeed(base: Float, sameKeyHeld: Boolean, heldMs: Long): Float {
        if (!sameKeyHeld || heldMs < SPEED_ACCEL_AFTER_MS) return base
        val accelerationDuration = heldMs - SPEED_ACCEL_AFTER_MS
        val speedMultiplier = 1.0f + (accelerationDuration / SPEED_ACCEL_RAMP_MS.toFloat()) * 3.0f
        return (base * speedMultiplier).coerceAtMost(base * SPEED_MAX_MULTIPLIER)
    }

    fun dpadDelta(dir: DpadDir, speed: Float): Pair<Float, Float> {
        return when (dir) {
            DpadDir.UP -> 0f to -speed
            DpadDir.DOWN -> 0f to speed
            DpadDir.LEFT -> -speed to 0f
            DpadDir.RIGHT -> speed to 0f
        }
    }

    /** `true` show, `false` hide, `null` leave as-is. */
    fun toolbarVisibilityForPointerY(pointerY: Float, density: Float): Boolean? {
        if (pointerY < TOOLBAR_SHOW_Y_DP * density) return true
        if (pointerY >= TOOLBAR_HIDE_Y_DP * density) return false
        return null
    }

    fun edgePan(
        deltaX: Float,
        deltaY: Float,
        pointerX: Float,
        pointerY: Float,
        hostWidth: Int,
        hostHeight: Int,
        thresholdPx: Float,
        step: Int
    ): Pair<Int, Int> {
        var dx = 0
        var dy = 0
        if (deltaX < 0 && pointerX <= thresholdPx) {
            dx = -step
        } else if (deltaX > 0 && pointerX >= hostWidth - 1 - thresholdPx) {
            dx = step
        }
        if (deltaY < 0 && pointerY <= thresholdPx) {
            dy = -step
        } else if (deltaY > 0 && pointerY >= hostHeight - 1 - thresholdPx) {
            dy = step
        }
        return dx to dy
    }
}
