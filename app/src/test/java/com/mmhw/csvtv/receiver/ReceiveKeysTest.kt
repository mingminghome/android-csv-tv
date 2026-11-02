package com.mmhw.csvtv.receiver

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiveKeysTest {
    @Test
    fun dpadAndMediaKeysMapToAlbumActions() {
        assertEquals(ReceiveKeys.NEXT, ReceiveKeys.albumAction(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ReceiveKeys.NEXT, ReceiveKeys.albumAction(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(ReceiveKeys.PREV, ReceiveKeys.albumAction(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ReceiveKeys.PREV, ReceiveKeys.albumAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(ReceiveKeys.PAUSE, ReceiveKeys.albumAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(ReceiveKeys.PAUSE, ReceiveKeys.albumAction(KeyEvent.KEYCODE_ENTER))
        assertEquals(ReceiveKeys.NONE, ReceiveKeys.albumAction(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(ReceiveKeys.NONE, ReceiveKeys.albumAction(KeyEvent.KEYCODE_BACK))
    }
}
