package com.mmhw.csvtv

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * TV stays landscape.
 * Browse (Settings, catalog, Send): phone Auto-rotate ON forces landscape
 * so the wide layout is used; OFF locks portrait.
 * Receive on a phone follows the sensor when Auto-rotate is ON so photos
 * and video can be portrait or landscape.
 */
object OrientationPolicy {
    enum class Mode { Browse, Receive }

    fun requestedOrientation(
        kind: DeviceProfile.Kind,
        autoRotateEnabled: Boolean,
        mode: Mode = Mode.Browse
    ): Int {
        if (kind == DeviceProfile.Kind.Television) {
            return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (!autoRotateEnabled) {
            return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        return if (mode == Mode.Receive) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun apply(activity: Activity, mode: Mode = Mode.Browse) {
        activity.requestedOrientation = requestedOrientation(
            kind = DeviceProfile.kind(activity),
            autoRotateEnabled = Utils.isAutoRotateEnabled(activity),
            mode = mode
        )
    }
}
