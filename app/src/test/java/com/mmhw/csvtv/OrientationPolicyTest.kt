package com.mmhw.csvtv

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationPolicyTest {

    @Test
    fun television_staysLandscapeEvenIfAutoRotatePrefIsOn() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            OrientationPolicy.requestedOrientation(isTelevision = true, autoRotateEnabled = true)
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            OrientationPolicy.requestedOrientation(isTelevision = true, autoRotateEnabled = false)
        )
    }

    @Test
    fun phone_forcesLandscapeWhenAutoRotateIsOn() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            OrientationPolicy.requestedOrientation(isTelevision = false, autoRotateEnabled = true)
        )
    }

    @Test
    fun phone_locksPortraitWhenAutoRotateIsOff() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationPolicy.requestedOrientation(isTelevision = false, autoRotateEnabled = false)
        )
    }

    @Test
    fun television_detectedFromUiModeOrLeanback() {
        assertTrue(DeviceProfile.isTelevision(Configuration.UI_MODE_TYPE_TELEVISION, false))
        assertTrue(DeviceProfile.isTelevision(Configuration.UI_MODE_TYPE_NORMAL, true))
        assertFalse(DeviceProfile.isTelevision(Configuration.UI_MODE_TYPE_NORMAL, false))
        assertFalse(DeviceProfile.isTelevision(Configuration.UI_MODE_TYPE_DESK, false))
    }
}
