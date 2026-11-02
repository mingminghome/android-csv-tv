package com.mmhw.csvtv

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView

/** Shared Back | title | overflow chrome for Settings, Send, and Receive. */
object AppToolbar {
    private const val NARROW_DP = 600

    fun isNarrow(activity: Activity): Boolean {
        return activity.resources.configuration.screenWidthDp < NARROW_DP
    }

    fun bind(
        activity: Activity,
        title: CharSequence,
        subtitle: CharSequence?,
        backText: String? = null,
        onBack: View.OnClickListener
    ) {
        val back = activity.findViewById<Button>(R.id.toolbar_back)
        val titleView = activity.findViewById<TextView>(R.id.toolbar_title)
        val subtitleView = activity.findViewById<TextView>(R.id.toolbar_subtitle)
        val narrow = isNarrow(activity)

        titleView.text = title
        if (subtitle.isNullOrBlank()) {
            subtitleView.text = ""
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.text = subtitle
            subtitleView.visibility = View.VISIBLE
        }

        back.text = backText ?: if (narrow) "" else activity.getString(R.string.send_close)
        back.setOnClickListener(onBack)
    }
}
