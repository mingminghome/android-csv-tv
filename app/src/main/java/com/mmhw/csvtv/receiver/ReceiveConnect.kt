package com.mmhw.csvtv.receiver

import android.content.Context
import com.mmhw.csvtv.R

/**
 * Incoming-cast gate. Confirmation is off by default, so Send plays at once.
 * Auto connect on Receive is the inverse of that setting.
 */
object ReceiveConnect {
    private const val PREFS = "AppPrefs"
    private const val KEY_CONFIRM = "receiver_confirm_connection"

    fun confirmEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONFIRM, false)
    }

    fun setConfirmEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM, enabled).apply()
    }

    fun autoConnect(context: Context): Boolean = !confirmEnabled(context)

    fun setAutoConnect(context: Context, enabled: Boolean) {
        setConfirmEnabled(context, !enabled)
    }

    fun confirmLabel(context: Context): String {
        return context.getString(
            if (confirmEnabled(context)) R.string.setup_confirm_on else R.string.setup_confirm_off
        )
    }

    fun autoConnectLabel(context: Context): String {
        return context.getString(
            if (autoConnect(context)) R.string.receive_auto_connect_on else R.string.receive_auto_connect_off
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
