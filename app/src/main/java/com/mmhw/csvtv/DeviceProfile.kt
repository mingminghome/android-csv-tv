package com.mmhw.csvtv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * TV vs handheld. Fire TV / Android TV report television UI mode or Leanback.
 * Phones and tablets do not, so they get auto-rotate and the narrow settings layout.
 */
object DeviceProfile {

    fun isTelevision(uiModeType: Int, hasLeanbackFeature: Boolean): Boolean {
        return uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || hasLeanbackFeature
    }

    fun isTelevision(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val uiModeType = uiMode?.currentModeType ?: Configuration.UI_MODE_TYPE_NORMAL
        val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return isTelevision(uiModeType, hasLeanback)
    }

    fun isHandheld(context: Context): Boolean = !isTelevision(context)
}
