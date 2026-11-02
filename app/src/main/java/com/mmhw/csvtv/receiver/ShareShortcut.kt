package com.mmhw.csvtv.receiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.mmhw.csvtv.R

/** Puts TV, Send in the system share sheet. */
object ShareShortcut {
    const val CATEGORY = "com.mmhw.csvtv.SHARE_SEND"

    fun publish(context: Context) {
        try {
            val app = context.applicationContext
            val shortcut = ShortcutInfoCompat.Builder(app, "tv_send")
                .setShortLabel(app.getString(R.string.send_share_label))
                .setLongLabel(app.getString(R.string.send_share_collection))
                .setIcon(IconCompat.createWithResource(app, R.drawable.ic_cast))
                .setCategories(setOf(CATEGORY))
                .setActivity(ComponentName(app, SendActivity::class.java))
                .setIntent(
                    Intent(Intent.ACTION_SEND)
                        .setClass(app, SendActivity::class.java)
                        .setType("*/*")
                )
                .setLongLived(true)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
        } catch (_: Exception) {
        }
    }
}
