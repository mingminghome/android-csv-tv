package com.mmhw.csvtv

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * TV stays landscape. On a phone, Auto-rotate ON forces landscape so the
 * wide Settings layout is used. Auto-rotate OFF locks portrait (narrow).
 */
object OrientationPolicy {

    fun requestedOrientation(isTelevision: Boolean, autoRotateEnabled: Boolean): Int {
        return when {
            isTelevision || autoRotateEnabled -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun apply(activity: Activity) {
        activity.requestedOrientation = requestedOrientation(
            isTelevision = DeviceProfile.isTelevision(activity),
            autoRotateEnabled = Utils.isAutoRotateEnabled(activity)
        )
    }
}
