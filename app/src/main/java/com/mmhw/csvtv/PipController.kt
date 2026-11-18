package com.mmhw.csvtv

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * One corner window over the stage. Fragments stay added; the corner is a
 * resized fragment, not a second activity. Close removes that fragment.
 */
class PipController(
    private val activity: FragmentActivity
) {
    var model = PipPolicy.Model()
        private set

    private var stage: Fragment? = null
    private var corner: Fragment? = null
    private var pending: Fragment? = null
    private var chrome: View? = null
    private var bar: LinearLayout? = null
    private var ring: View? = null
    private var label: TextView? = null
    private var speaker: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var longBackArmed = false
    private var longBackFired = false

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (model.focus == PipPolicy.Focus.CORNER || model.barOpen) {
                dispatch(KeyEvent.KEYCODE_BACK, longBack = false)
                return
            }
            isEnabled = false
            activity.onBackPressedDispatcher.onBackPressed()
            isEnabled = true
            promoteBackCallback()
        }
    }

    private val longBackRunnable = Runnable {
        if (!longBackArmed) return@Runnable
        longBackFired = true
        longBackArmed = false
        dispatch(KeyEvent.KEYCODE_BACK, longBack = true)
    }

    fun install() {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = activity.layoutInflater.inflate(R.layout.pip_chrome, content, false)
        view.elevation = 40f
        content.addView(view)
        chrome = view
        bar = view.findViewById(R.id.pip_bar)
        ring = view.findViewById(R.id.pip_ring)
        label = view.findViewById(R.id.pip_label)
        speaker = view.findViewById(R.id.pip_speaker)
        bar?.apply {
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
    }

    fun attach(fragment: Fragment) {
        stage = fragment
        model = PipPolicy.Model()
    }

    /**
     * @return false when both slots are full, or state is already saved.
     * A true result may still be waiting on Replace / Play in the corner.
     */
    fun openStage(fragment: Fragment): Boolean {
        val kind = kindOf(fragment) ?: return false
        if (model.stage != PipPolicy.Kind.NONE && model.corner != PipPolicy.Kind.NONE) {
            Toast.makeText(activity, "Close the small window first.", Toast.LENGTH_SHORT).show()
            return false
        }
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return false
        if (model.pendingChoice || (model.stage != PipPolicy.Kind.NONE && model.corner == PipPolicy.Kind.NONE)) {
            pending = fragment
            model = PipPolicy.beginChoice(model)
            refreshChrome()
            return true
        }
        return commitOpen(fragment, kind)
    }

    private fun commitOpen(fragment: Fragment, kind: PipPolicy.Kind): Boolean {
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return false
        val tx = fm.beginTransaction()
        stage?.let { current ->
            if (current !== corner && current !== fragment && current.isAdded) tx.hide(current)
        }
        tx.add(R.id.fragment_container, fragment)
        tx.commitNow()
        stage = fragment
        model = PipPolicy.afterOpen(model, kind)
        maybeCapCorner()
        applySound()
        applyLayouts()
        refreshChrome()
        return true
    }

    /**
     * Native handoff hides the page and puts the player on the stage.
     * The page is not the corner unless the user minimizes.
     */
    /** @return false when state is saved and the player was not added. */
    fun handoff(web: Fragment, player: Fragment): Boolean {
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return false
        val tx = fm.beginTransaction().hide(web).add(R.id.fragment_container, player)
        tx.commitNow()
        if (stage === web || stage == null) stage = player
        model = PipPolicy.afterOpen(model, PipPolicy.Kind.PLAYER)
        maybeCapCorner()
        applySound()
        applyLayouts()
        refreshChrome()
        return true
    }

    /** Swap a stage that cannot play for a page, without touching the corner. */
    fun swapStage(current: Fragment, next: Fragment) {
        if (current === corner) {
            closeCorner()
            return
        }
        val kind = kindOf(next) ?: return
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return
        val tx = fm.beginTransaction()
        if (current === stage && current.isAdded) tx.remove(current)
        tx.add(R.id.fragment_container, next)
        tx.commitNow()
        stage = next
        model = PipPolicy.afterOpen(model, kind)
        maybeCapCorner()
        applySound()
        applyLayouts()
        refreshChrome()
    }

    fun removeFromStage(fragment: Fragment) {
        if (fragment === corner) {
            closeCorner()
            return
        }
        if (fragment !== stage) return
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return
        val tx = fm.beginTransaction().remove(fragment)
        val reveal = hiddenExcept(fragment)
        if (reveal != null) tx.show(reveal)
        tx.commitNow()
        stage = reveal
        model = model.copy(
            stage = kindOf(reveal) ?: PipPolicy.Kind.NONE,
            soundOnCorner = false,
            focus = PipPolicy.Focus.STAGE,
            barOpen = false
        )
        applySound()
        applyLayouts()
        refreshChrome()
    }

    fun closeAll() {
        pending = null
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return
        val tx = fm.beginTransaction()
        listOfNotNull(stage, corner).forEach { fragment ->
            if (fragment !is MainFragment && fragment.isAdded) tx.remove(fragment)
        }
        fm.fragments.filterIsInstance<MainFragment>().firstOrNull()?.let { main ->
            tx.show(main)
            stage = main
        }
        tx.commitNow()
        corner = null
        model = PipPolicy.Model()
        applyLayouts()
        refreshChrome()
    }

    /** Phone Minimize icon. Refuses a third surface. */
    fun minimizeIfStage(fragment: Fragment) {
        if (fragment !== stage || fragment !is PipSurface) return
        if (model.corner != PipPolicy.Kind.NONE) {
            Toast.makeText(activity, "Close the small window first.", Toast.LENGTH_SHORT).show()
            return
        }
        minimize()
    }

    /**
     * Catalog clicked a title that is already open.
     * The corner expands. The large window stays as it is. A second copy is not started.
     */
    fun focusExisting(url: String): Boolean {
        val slot = PipPolicy.slotFor(sourceKeys(stage), sourceKeys(corner), url)
        if (slot == PipPolicy.Slot.NONE) return false
        if (slot == PipPolicy.Slot.CORNER) {
            perform(PipPolicy.Action.SWAP)
            if (PipPolicy.slotFor(emptyList(), sourceKeys(corner), url) == PipPolicy.Slot.CORNER) {
                closeCorner()
            }
        }
        return true
    }

    /** Tap path for the bar. */
    fun perform(action: PipPolicy.Action) {
        applyStep(PipPolicy.activate(model, action))
    }

    fun onKeyDown(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            model.focus == PipPolicy.Focus.CORNER &&
            event.repeatCount == 0 &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            longBackArmed = true
            longBackFired = false
            handler.removeCallbacks(longBackRunnable)
            handler.postDelayed(longBackRunnable, LONG_BACK_MS)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        // Let a text field keep the letter P.
        if (event.keyCode == KeyEvent.KEYCODE_P && activity.currentFocus is android.widget.EditText) {
            return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && model.focus == PipPolicy.Focus.CORNER) {
            return true
        }
        return dispatch(event.keyCode, longBack = false)
    }

    fun onKeyUp(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && (longBackArmed || longBackFired)) {
            handler.removeCallbacks(longBackRunnable)
            val fired = longBackFired
            longBackArmed = false
            longBackFired = false
            if (!fired && model.focus == PipPolicy.Focus.CORNER) {
                dispatch(KeyEvent.KEYCODE_BACK, longBack = false)
            }
            return true
        }
        // Swallow the key-up that matches a bar or corner action so the stage
        // does not also see it (a player would treat Center-up as pause).
        if (model.focus == PipPolicy.Focus.CORNER || model.barOpen) {
            val step = PipPolicy.onKey(model, event.keyCode, longBack = false)
            return step.effect != PipPolicy.Effect.FALL_THROUGH
        }
        return false
    }

    private fun dispatch(keyCode: Int, longBack: Boolean): Boolean {
        return applyStep(PipPolicy.onKey(model, keyCode, longBack))
    }

    private fun applyStep(step: PipPolicy.Step): Boolean {
        if (step.effect == PipPolicy.Effect.FALL_THROUGH) return false
        if (step.effect == PipPolicy.Effect.MINIMIZE) {
            minimize()
            return true
        }
        model = step.model
        when (step.effect) {
            PipPolicy.Effect.CLOSE_CORNER -> closeCorner()
            PipPolicy.Effect.SWAP -> applySwap()
            PipPolicy.Effect.TOGGLE_AUDIO -> applySound()
            PipPolicy.Effect.CYCLE_PLACE, PipPolicy.Effect.TOGGLE_SIZE -> applyLayouts()
            PipPolicy.Effect.REPLACE_STAGE -> commitPending(parkCurrent = false)
            PipPolicy.Effect.PLAY_IN_CORNER -> commitPending(parkCurrent = true)
            PipPolicy.Effect.CANCEL_CHOICE -> dropPending()
            else -> Unit
        }
        refreshChrome()
        return true
    }

    private fun minimize() {
        val leaving = stage as? PipSurface ?: return
        if (model.corner != PipPolicy.Kind.NONE) return
        val fragment = leaving as Fragment
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return
        val reveal = hiddenExcept(fragment)
        if (reveal != null) {
            fm.beginTransaction().show(reveal).commitNow()
        }
        corner = fragment
        stage = reveal
        model = PipPolicy.afterMinimize(model, kindOf(reveal) ?: PipPolicy.Kind.NONE)
        applySound()
        applyLayouts()
        refreshChrome()
    }

    private fun applySwap() {
        val cornerFragment = corner
        val stageFragment = stage
        if (cornerFragment == null) return
        if (model.corner == PipPolicy.Kind.NONE) {
            // Expanded: the old corner is now the only media on stage.
            stage = cornerFragment
            corner = null
            if (stageFragment != null &&
                stageFragment !== cornerFragment &&
                stageFragment.isAdded &&
                !activity.supportFragmentManager.isStateSaved
            ) {
                activity.supportFragmentManager.beginTransaction().hide(stageFragment).commitNow()
            }
        } else if (stageFragment != null && stageFragment !== cornerFragment) {
            val newCorner = stageFragment
            stage = cornerFragment
            corner = newCorner
        }
        applySound()
        applyLayouts()
    }

    private fun commitPending(parkCurrent: Boolean) {
        val fragment = pending ?: return
        pending = null
        val kind = kindOf(fragment) ?: return
        if (parkCurrent && stage is PipSurface && model.corner == PipPolicy.Kind.NONE) {
            val leaving = stage as Fragment
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved) {
                dropPending()
                return
            }
            fm.beginTransaction().add(R.id.fragment_container, fragment).commitNow()
            corner = leaving
            stage = fragment
            model = PipPolicy.afterOpen(PipPolicy.afterMinimize(model, kind), kind)
            maybeCapCorner()
            applySound()
            applyLayouts()
            return
        }
        val current = stage
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return
        if (current != null && current !is MainFragment && current.isAdded && current !== corner) {
            fm.beginTransaction().remove(current).commitNow()
        }
        stage = hiddenExcept(fragment)
        model = model.copy(
            stage = kindOf(stage) ?: PipPolicy.Kind.NONE,
            soundOnCorner = false,
            pendingChoice = false
        )
        commitOpen(fragment, kind)
    }

    private fun dropPending() {
        pending = null
        activity.supportFragmentManager.fragments
            .filterIsInstance<MainFragment>()
            .firstOrNull()
            ?.clearOpeningCard()
    }

    /**
     * A second player on a low-RAM device is capped before its first frame.
     * The corner is already decoding; retuning it reloads that stream and a
     * single-variant channel comes back black or audio-only.
     */
    private fun maybeCapCorner() {
        if (PipPolicy.capCornerDecoder(cornerIsLowRam(), model.stage, model.corner)) {
            (stage as? PipSurface)?.capDecoder()
        }
    }

    private fun closeCorner() {
        val fragment = corner ?: return
        corner = null
        model = model.copy(
            corner = PipPolicy.Kind.NONE,
            soundOnCorner = false,
            focus = PipPolicy.Focus.STAGE,
            barOpen = false
        )
        val fm = activity.supportFragmentManager
        if (fragment.isAdded && !fm.isStateSaved) {
            fm.beginTransaction().remove(fragment).commitNow()
        }
        applySound()
        applyLayouts()
        refreshChrome()
    }

    private fun applySound() {
        val cornerAudible = model.soundOnCorner && model.corner != PipPolicy.Kind.NONE
        (stage as? PipSurface)?.setAudible(!cornerAudible)
        (corner as? PipSurface)?.setAudible(cornerAudible)
    }

    private fun applyLayouts() {
        (stage as? PipSurface)?.setCorner(false)
        layoutFull(stage?.view)
        val cornerFragment = corner
        if (cornerFragment == null) {
            ring?.visibility = View.GONE
            return
        }
        (cornerFragment as? PipSurface)?.setCorner(true)
        layoutCorner(cornerFragment.view)
        cornerFragment.view?.bringToFront()
        chrome?.bringToFront()
        positionRing()
    }

    private fun layoutFull(view: View?) {
        val target = view ?: return
        val lp = target.layoutParams as? FrameLayout.LayoutParams
        val alreadyFull = lp != null &&
            lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            lp.height == ViewGroup.LayoutParams.MATCH_PARENT &&
            lp.leftMargin == 0 && lp.topMargin == 0 &&
            lp.rightMargin == 0 && lp.bottomMargin == 0 &&
            target.elevation == 0f
        if (alreadyFull) return
        target.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        target.elevation = 0f
    }

    private fun layoutCorner(view: View?) {
        val target = view ?: return
        val metrics = activity.resources.displayMetrics
        val fraction = if (model.size == PipPolicy.Size.MINI) 0.28f else 0.40f
        val width = (metrics.widthPixels * fraction).toInt().coerceAtLeast(1)
        val height = width * 9 / 16
        val margin = (24 * metrics.density).toInt()
        val topExtra = if (model.place == PipPolicy.Place.TOP_LEFT ||
            model.place == PipPolicy.Place.TOP_RIGHT
        ) {
            (56 * metrics.density).toInt()
        } else {
            0
        }
        val gravity = when (model.place) {
            PipPolicy.Place.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            PipPolicy.Place.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            PipPolicy.Place.TOP_LEFT -> Gravity.TOP or Gravity.START
            PipPolicy.Place.TOP_RIGHT -> Gravity.TOP or Gravity.END
        }
        val existing = target.layoutParams as? FrameLayout.LayoutParams
        val unchanged = existing != null &&
            existing.width == width &&
            existing.height == height &&
            existing.gravity == gravity &&
            existing.leftMargin == margin &&
            existing.rightMargin == margin &&
            existing.topMargin == margin + topExtra &&
            existing.bottomMargin == margin &&
            target.elevation == 24f
        if (unchanged) return
        val lp = FrameLayout.LayoutParams(width, height, gravity)
        lp.setMargins(margin, margin + topExtra, margin, margin)
        target.layoutParams = lp
        target.elevation = 24f
    }

    private fun refreshChrome() {
        val items = PipPolicy.actions(model)
        val handheld = DeviceProfile.isHandheld(activity)
        // One bar for the remote and for touch. A phone keeps it next to the corner.
        val showBar = items.isNotEmpty() &&
            (model.barOpen || (handheld && model.corner != PipPolicy.Kind.NONE))
        bar?.visibility = if (showBar) View.VISIBLE else View.GONE
        if (showBar) fillBar(items)
        positionBar(showBar)
        val showRing = model.corner != PipPolicy.Kind.NONE &&
            (model.focus == PipPolicy.Focus.CORNER || model.barOpen)
        ring?.visibility = if (showRing) View.VISIBLE else View.GONE
        if (showRing) {
            label?.text = (corner as? PipSurface)?.pipLabel() ?: "Window"
            positionRing()
        }
        positionSpeaker()
        syncBrowseNetwork()
        promoteBackCallback()
        syncPlaybackFullscreen()
    }

    /** Hide the phone status and navigation bars while a video or page fills the stage. */
    fun syncPlaybackFullscreen() {
        if (!DeviceProfile.isHandheld(activity)) return
        val playing = model.stage == PipPolicy.Kind.PLAYER || model.stage == PipPolicy.Kind.WEB
        AppChrome.setPlaybackFullscreen(activity, playing)
    }

    private fun positionSpeaker() {
        val mark = speaker ?: return
        val show = model.corner != PipPolicy.Kind.NONE
        mark.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val density = activity.resources.displayMetrics.density
        val size = (28 * density).toInt()
        val margin = (16 * density).toInt()
        mark.post {
            if (model.soundOnCorner) {
                val target = corner?.view ?: return@post
                if (target.width <= 0) return@post
                val location = IntArray(2)
                val hostLocation = IntArray(2)
                target.getLocationOnScreen(location)
                (chrome?.parent as? View)?.getLocationOnScreen(hostLocation)
                val lp = FrameLayout.LayoutParams(size, size)
                lp.leftMargin = location[0] - hostLocation[0] + target.width - size - margin
                lp.topMargin = location[1] - hostLocation[1] + target.height - size - margin
                mark.layoutParams = lp
            } else {
                val onLeft = model.place == PipPolicy.Place.TOP_RIGHT ||
                    model.place == PipPolicy.Place.BOTTOM_RIGHT
                val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
                lp.leftMargin = if (onLeft) {
                    margin
                } else {
                    activity.resources.displayMetrics.widthPixels - size - margin
                }
                lp.topMargin = margin
                mark.layoutParams = lp
            }
        }
    }

    /** Catalog probes stay quiet while any video or page is up, including a corner. */
    private fun syncBrowseNetwork() {
        val main = activity.supportFragmentManager.fragments
            .filterIsInstance<MainFragment>()
            .firstOrNull() ?: return
        val mediaUp = model.corner != PipPolicy.Kind.NONE ||
            (stage != null && stage !== main)
        if (main.isHidden || mediaUp) main.pauseCatalogNetworkForPip()
        else main.resumeCatalogNetworkForPip()
    }

    private fun fillBar(items: List<PipPolicy.Action>) {
        val row = bar ?: return
        row.removeAllViews()
        val index = model.barIndex.coerceIn(0, items.lastIndex)
        val selected = activity.getDrawable(R.drawable.pip_bar_selected)
        items.forEachIndexed { i, action ->
            val button = TextView(activity)
            button.text = PipPolicy.label(action, model.soundOnCorner)
            button.textSize = 16f
            button.gravity = Gravity.CENTER
            button.setTextColor(Color.WHITE)
            button.setPadding(22, 12, 22, 12)
            val chosen = model.barOpen && i == index
            button.alpha = if (!model.barOpen || chosen) 1f else 0.55f
            if (chosen) button.background = selected?.constantState?.newDrawable()
            button.isClickable = true
            button.setOnClickListener { perform(action) }
            row.addView(button)
        }
    }

    /**
     * With a corner, the bar sits centered on that window, just outside it.
     * Alone, it stays centered on the screen and above the player controls.
     */
    private fun positionBar(show: Boolean) {
        val row = bar ?: return
        if (!show) return
        val target = if (model.corner != PipPolicy.Kind.NONE) corner?.view else null
        if (target == null) {
            val lp = row.layoutParams as? FrameLayout.LayoutParams ?: return
            val density = activity.resources.displayMetrics.density
            val aboveTransport = model.stage == PipPolicy.Kind.PLAYER
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.leftMargin = 0
            lp.topMargin = 0
            lp.bottomMargin = ((if (aboveTransport) 88 else 28) * density).toInt()
            row.layoutParams = lp
            return
        }
        target.post { placeBarBeside(row, target) }
    }

    private fun placeBarBeside(row: View, target: View) {
        if (row.visibility != View.VISIBLE) return
        val width = target.width
        val height = target.height
        if (width <= 0 || height <= 0) return
        val location = IntArray(2)
        val hostLocation = IntArray(2)
        target.getLocationOnScreen(location)
        (chrome?.parent as? View)?.getLocationOnScreen(hostLocation)
        val density = activity.resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        row.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        )
        val rowW = row.measuredWidth
        val rowH = row.measuredHeight
        val cornerLeft = location[0] - hostLocation[0]
        val cornerTop = location[1] - hostLocation[1]
        val below = model.place == PipPolicy.Place.TOP_LEFT ||
            model.place == PipPolicy.Place.TOP_RIGHT
        var top = if (below) cornerTop + height + gap else cornerTop - rowH - gap
        if (top < gap) top = cornerTop + height + gap
        if (top + rowH > screenH - gap) top = (cornerTop - rowH - gap).coerceAtLeast(gap)
        val left = (cornerLeft + (width - rowW) / 2)
            .coerceIn(gap, (screenW - rowW - gap).coerceAtLeast(gap))
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = left
        lp.topMargin = top
        row.layoutParams = lp
    }

    private fun sourceKeys(fragment: Fragment?): List<String> {
        val args = fragment?.arguments ?: return emptyList()
        return listOf("catalog_url", "video_url", WebViewFragment.ARG_URL).mapNotNull { key ->
            args.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun positionRing() {
        val target = corner?.view ?: return
        val frame = ring ?: return
        target.post {
            val width = target.width
            val height = target.height
            if (width <= 0 || height <= 0) return@post
            val location = IntArray(2)
            val hostLocation = IntArray(2)
            target.getLocationOnScreen(location)
            (chrome?.parent as? View)?.getLocationOnScreen(hostLocation)
            val lp = FrameLayout.LayoutParams(width, height)
            lp.leftMargin = location[0] - hostLocation[0]
            lp.topMargin = location[1] - hostLocation[1]
            frame.layoutParams = lp
        }
    }

    private fun hiddenExcept(skip: Fragment): Fragment? {
        val fragments = activity.supportFragmentManager.fragments
        val main = fragments.filterIsInstance<MainFragment>().firstOrNull()
        return PipPolicy.selectRevealedFragment(
            fragments = fragments,
            skip = skip,
            corner = corner,
            isHidden = { it.isHidden },
            isAdded = { it.isAdded },
            fallback = main
        )
    }

    private fun cornerIsLowRam(): Boolean {
        return (corner as? PlaybackFragment)?.isLowRam() == true ||
            (stage as? PlaybackFragment)?.isLowRam() == true
    }

    private fun promoteBackCallback() {
        backCallback.remove()
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
    }

    private fun kindOf(fragment: Fragment?): PipPolicy.Kind? {
        return when (fragment) {
            is PipSurface -> fragment.pipKind
            is MainFragment -> PipPolicy.Kind.NONE
            else -> null
        }
    }

    companion object {
        private const val LONG_BACK_MS = 700L
    }
}

interface PipSurface {
    val pipKind: PipPolicy.Kind
    fun setCorner(corner: Boolean)
    fun setAudible(audible: Boolean)
    fun capDecoder()
    fun pipLabel(): String
}

/** Drop a stage fragment without popping a corner that shares the container. */
fun Fragment.leaveStage() {
    val pip = (activity as? MainActivity)?.pip
    if (pip != null) {
        pip.removeFromStage(this)
        return
    }
    if (!parentFragmentManager.isStateSaved) {
        parentFragmentManager.popBackStack()
    }
}
