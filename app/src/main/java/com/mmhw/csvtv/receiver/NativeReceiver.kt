package com.mmhw.csvtv.receiver

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * JNI front for the LAN send/receive library.
 * Events arrive on a native thread and are posted to the main looper.
 */
object NativeReceiver {
    const val EVENT_READY = 1
    const val EVENT_PLAY = 2
    const val EVENT_PAUSE = 3
    const val EVENT_STOP = 4
    const val EVENT_VOLUME = 5
    const val EVENT_ERROR = 6

    const val MEDIA_UNKNOWN = 0
    const val MEDIA_IMAGE = 1
    const val MEDIA_VIDEO = 2
    const val MEDIA_AUDIO = 3
    const val MEDIA_WEB = 4

    interface Listener {
        fun onReceiverEvent(event: ReceiverNativeEvent)
    }

    @Volatile
    var available: Boolean = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    init {
        available = try {
            System.loadLibrary("castify")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("NativeReceiver", "native library missing — rebuild with the NDK", e)
            false
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    external fun nativeStart(name: String, uuid: String, bindIp: String): Int
    external fun nativeStop()

    /** Empty string turns LAN media encryption off. */
    external fun nativeSetPassphrase(passphrase: String)

    @JvmStatic
    fun onNativeEvent(kind: Int, mediaKind: Int, uri: String?, mime: String?, title: String?) {
        val event = ReceiverNativeEvent(
            kind = kind,
            mediaKind = mediaKind,
            uri = uri.orEmpty(),
            mime = mime?.takeIf { it.isNotBlank() },
            title = title?.takeIf { it.isNotBlank() }
        )
        main.post {
            listeners.forEach { it.onReceiverEvent(event) }
        }
    }
}

data class ReceiverNativeEvent(
    val kind: Int,
    val mediaKind: Int,
    val uri: String,
    val mime: String?,
    val title: String?
)
