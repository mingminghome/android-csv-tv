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
import com.mmhw.csvtv.DeviceProfile
import com.mmhw.csvtv.OrientationPolicy
import com.mmhw.csvtv.R
import com.mmhw.csvtv.Utils

/**
 * Shared 3-dot Cast menu for Send and Receive: Auto connect and Encryption.
 * Receive on a phone also has Auto-rotate.
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

    fun showAutoRotateItem(receiveHost: Boolean, handheld: Boolean): Boolean {
        return receiveHost && handheld
    }

    fun attach(
        activity: Activity,
        showAutoRotate: Boolean = false,
        onChanged: () -> Unit = {}
    ) {
        dismiss()
        val more = activity.findViewById<ImageButton>(R.id.toolbar_more) ?: return
        more.visibility = View.VISIBLE
        more.setOnClickListener { show(activity, more, onChanged, showAutoRotate) }
        val owner = activity as? LifecycleOwner ?: return
        if (observed.add(owner)) {
            owner.lifecycle.addObserver(dismissOnPause)
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun show(
        activity: Activity,
        anchor: View,
        onChanged: () -> Unit,
        showAutoRotate: Boolean
    ) {
        dismiss()
        val content = LayoutInflater.from(activity).inflate(R.layout.popup_cast_overflow, null)
        val autoBtn = content.findViewById<Button>(R.id.overflow_auto_connect)
        val encBtn = content.findViewById<Button>(R.id.overflow_encryption)
        val rotateBtn = content.findViewById<Button>(R.id.overflow_auto_rotate)
        val rotateVisible = showAutoRotateItem(
            receiveHost = showAutoRotate,
            handheld = DeviceProfile.isHandheld(activity)
        )
        rotateBtn.visibility = if (rotateVisible) View.VISIBLE else View.GONE
        paint(activity, autoBtn, encBtn, rotateBtn)

        autoBtn.setOnClickListener {
            ReceiveConnect.setAutoConnect(activity, !ReceiveConnect.autoConnect(activity))
            paint(activity, autoBtn, encBtn, rotateBtn)
            onChanged()
        }
        encBtn.setOnClickListener {
            val applied = CastCrypto.toggle(activity) {
                paint(activity, autoBtn, encBtn, rotateBtn)
                onChanged()
            }
            if (!applied) {
                dismiss()
            }
        }
        rotateBtn.setOnClickListener {
            val next = !Utils.isAutoRotateEnabled(activity)
            Utils.setAutoRotateEnabled(activity, next)
            val mode = if (activity is ReceiveActivity) {
                OrientationPolicy.Mode.Receive
            } else {
                OrientationPolicy.Mode.Browse
            }
            OrientationPolicy.apply(activity, mode)
            paint(activity, autoBtn, encBtn, rotateBtn)
            onChanged()
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

    private fun paint(activity: Activity, autoBtn: Button, encBtn: Button, rotateBtn: Button) {
        autoBtn.text = ReceiveConnect.autoConnectLabel(activity)
        encBtn.text = CastCrypto.buttonLabel(activity)
        rotateBtn.text = activity.getString(
            if (Utils.isAutoRotateEnabled(activity)) {
                R.string.setup_auto_rotate_on
            } else {
                R.string.setup_auto_rotate_off
            }
        )
    }

    private fun dp(activity: Activity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
