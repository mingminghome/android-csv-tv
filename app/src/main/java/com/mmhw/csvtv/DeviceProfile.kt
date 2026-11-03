package com.mmhw.csvtv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Single TV vs phone/tablet check for orientation, Settings, and chrome.
 * Fire TV / Android TV report television UI mode or Leanback.
 */
object DeviceProfile {
    enum class Kind { Television, Handheld }

    fun kind(uiModeType: Int, hasLeanbackFeature: Boolean): Kind {
        return if (uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || hasLeanbackFeature) {
            Kind.Television
        } else {
            Kind.Handheld
        }
    }

    fun kind(context: Context): Kind {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val uiModeType = uiMode?.currentModeType ?: Configuration.UI_MODE_TYPE_NORMAL
        val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return kind(uiModeType, hasLeanback)
    }

    fun isTelevision(uiModeType: Int, hasLeanbackFeature: Boolean): Boolean =
        kind(uiModeType, hasLeanbackFeature) == Kind.Television

    fun isTelevision(context: Context): Boolean = kind(context) == Kind.Television

    fun isHandheld(context: Context): Boolean = kind(context) == Kind.Handheld
}
