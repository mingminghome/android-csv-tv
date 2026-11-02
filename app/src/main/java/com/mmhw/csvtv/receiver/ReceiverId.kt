package com.mmhw.csvtv.receiver

import android.content.Context
import com.mmhw.csvtv.R
import java.util.UUID

/**
 * Stable per-install receiver identity. The short id is what Send (and
 * other players) show when several TVs advertise the same product name.
 */
object ReceiverId {
    private const val PREFS = "AppPrefs"
    private const val KEY = "receiver_uuid"

    fun uuid(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, created).apply()
        return created
    }

    /** Four-character code from the receiver UUID, e.g. `7C2A`. */
    fun shortId(uuid: String): String {
        val hex = uuid.trim()
            .removePrefix("uuid:")
            .removePrefix("UUID:")
            .filter { it.isLetterOrDigit() }
            .uppercase()
        if (hex.isEmpty()) return "0000"
        return hex.takeLast(4).padStart(4, '0')
    }

    fun shortId(context: Context): String = shortId(uuid(context))

    /** Advertised friendly name, unique on the LAN. */
    fun displayName(context: Context): String {
        return context.getString(R.string.receiver_device_name_id, shortId(context))
    }

    fun listLabel(name: String, uuid: String): String {
        val short = shortId(uuid)
        if (short == "0000") return name
        if (name.contains(short, ignoreCase = true)) return name
        return "$name · $short"
    }
}
