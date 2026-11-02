package com.mmhw.csvtv.receiver

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupWindow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.mmhw.csvtv.R

/**
 * Shared 3-dot Cast menu for Send and Receive: Auto connect and Encryption.
 */
object CastOverflow {
    private var popup: PopupWindow? = null
    private val observed = mutableSetOf<LifecycleOwner>()
    private val dismissOnPause = object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_PAUSE -> dismiss()
                Lifecycle.Event.ON_DESTROY -> {
                    dismiss()
                    source.lifecycle.removeObserver(this)
                    observed.remove(source)
                }
                else -> {}
            }
        }
    }

    fun attach(activity: Activity, onChanged: () -> Unit = {}) {
        dismiss()
        val more = activity.findViewById<ImageButton>(R.id.toolbar_more) ?: return
        more.visibility = View.VISIBLE
        more.setOnClickListener { show(activity, more, onChanged) }
        val owner = activity as? LifecycleOwner ?: return
        if (observed.add(owner)) {
            owner.lifecycle.addObserver(dismissOnPause)
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun show(activity: Activity, anchor: View, onChanged: () -> Unit) {
        dismiss()
        val content = LayoutInflater.from(activity).inflate(R.layout.popup_cast_overflow, null)
        val autoBtn = content.findViewById<Button>(R.id.overflow_auto_connect)
        val encBtn = content.findViewById<Button>(R.id.overflow_encryption)
        paint(activity, autoBtn, encBtn)

        autoBtn.setOnClickListener {
            ReceiveConnect.setAutoConnect(activity, !ReceiveConnect.autoConnect(activity))
            paint(activity, autoBtn, encBtn)
            onChanged()
        }
        encBtn.setOnClickListener {
            val applied = CastCrypto.toggle(activity) {
                paint(activity, autoBtn, encBtn)
                onChanged()
            }
            if (!applied) {
                dismiss()
            }
        }

        val window = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 12f * activity.resources.displayMetrics.density
            setOnDismissListener { if (popup === this) popup = null }
        }
        popup = window
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val xOff = anchor.width - content.measuredWidth
        window.showAsDropDown(anchor, xOff, dp(activity, 4))
        autoBtn.requestFocus()
    }

    private fun paint(activity: Activity, autoBtn: Button, encBtn: Button) {
        autoBtn.text = ReceiveConnect.autoConnectLabel(activity)
        encBtn.text = CastCrypto.buttonLabel(activity)
    }

    private fun dp(activity: Activity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
