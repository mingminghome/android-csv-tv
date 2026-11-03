package com.mmhw.csvtv

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Shared activity chrome for Settings, Send, Receive, and Main:
 * orientation, keep-awake, and system-bar padding.
 */
object AppChrome {
    fun prepare(
        activity: Activity,
        orientation: OrientationPolicy.Mode = OrientationPolicy.Mode.Browse
    ) {
        OrientationPolicy.apply(activity, orientation)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun padSystemBars(activity: Activity, edgeToEdge: () -> Boolean = { false }) {
        val content = activity.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            if (edgeToEdge()) {
                v.setPadding(0, 0, 0, 0)
            } else {
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
    }

    fun requestInsets(activity: Activity) {
        ViewCompat.requestApplyInsets(activity.findViewById(android.R.id.content))
    }
}
