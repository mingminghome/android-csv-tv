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
            OrientationPolicy.requestedOrientation(
                kind = DeviceProfile.Kind.Television,
                autoRotateEnabled = true
            )
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            OrientationPolicy.requestedOrientation(
                kind = DeviceProfile.Kind.Television,
                autoRotateEnabled = false
            )
        )
    }

    @Test
    fun phone_forcesLandscapeWhenAutoRotateIsOn() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            OrientationPolicy.requestedOrientation(
                kind = DeviceProfile.Kind.Handheld,
                autoRotateEnabled = true
            )
        )
    }

    @Test
    fun phone_locksPortraitWhenAutoRotateIsOff() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationPolicy.requestedOrientation(
                kind = DeviceProfile.Kind.Handheld,
                autoRotateEnabled = false
            )
        )
    }

    @Test
    fun phone_receive_followsSensorWhenAutoRotateIsOn() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            OrientationPolicy.requestedOrientation(
                kind = DeviceProfile.Kind.Handheld,
                autoRotateEnabled = true,
                mode = OrientationPolicy.Mode.Receive
            )
        )
    }

    @Test
    fun phone_receive_locksPortraitWhenAutoRotateIsOff() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationPolicy.requestedOrientation(
                kind = DeviceProfile.Kind.Handheld,
                autoRotateEnabled = false,
                mode = OrientationPolicy.Mode.Receive
            )
        )
    }

    @Test
    fun television_receive_staysLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            OrientationPolicy.requestedOrientation(
                kind = DeviceProfile.Kind.Television,
                autoRotateEnabled = true,
                mode = OrientationPolicy.Mode.Receive
            )
        )
    }

    @Test
    fun television_detectedFromUiModeOrLeanback() {
        assertEquals(
            DeviceProfile.Kind.Television,
            DeviceProfile.kind(Configuration.UI_MODE_TYPE_TELEVISION, false)
        )
        assertEquals(
            DeviceProfile.Kind.Television,
            DeviceProfile.kind(Configuration.UI_MODE_TYPE_NORMAL, true)
        )
        assertEquals(
            DeviceProfile.Kind.Handheld,
            DeviceProfile.kind(Configuration.UI_MODE_TYPE_NORMAL, false)
        )
        assertEquals(
            DeviceProfile.Kind.Handheld,
            DeviceProfile.kind(Configuration.UI_MODE_TYPE_DESK, false)
        )
        assertTrue(DeviceProfile.isTelevision(Configuration.UI_MODE_TYPE_TELEVISION, false))
        assertTrue(DeviceProfile.isTelevision(Configuration.UI_MODE_TYPE_NORMAL, true))
        assertFalse(DeviceProfile.isTelevision(Configuration.UI_MODE_TYPE_NORMAL, false))
        assertFalse(DeviceProfile.isTelevision(Configuration.UI_MODE_TYPE_DESK, false))
    }
}
