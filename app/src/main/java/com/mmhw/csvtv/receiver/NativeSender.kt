package com.mmhw.csvtv.receiver

/**
 * JNI front for the sender half of the LAN library (discover + play).
 * Search and play block; call them off the main thread.
 */
object NativeSender {
    val available: Boolean get() = NativeReceiver.available

    external fun nativeSearch(timeoutMs: Int): String?
    external fun nativePlay(controlUrl: String, mediaUrl: String, title: String, mime: String): Int
    external fun nativeStopPlay(controlUrl: String): Int
    external fun nativeServe(filePath: String, mime: String): String?
    external fun nativeServeStop()
}
