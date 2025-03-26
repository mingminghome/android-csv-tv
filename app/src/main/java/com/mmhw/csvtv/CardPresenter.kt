package com.mmhw.csvtv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.FitCenter

class CardPresenter(private val fragment: Fragment) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_compact_card, parent, false)

        val titleText = view.findViewById<TextView>(R.id.title_text)

        view.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus ->
                titleText.visibility = if (hasFocus) View.VISIBLE else View.GONE
            }
        }

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as? Video ?: return
        val view = viewHolder.view

        val thumbnailImage = view.findViewById<ImageView>(R.id.thumbnail_image)
        val titleText = view.findViewById<TextView>(R.id.title_text)

        titleText.text = video.title

        val context = fragment.requireContext()
        val placeholderIcon = ContextCompat.getDrawable(context, R.drawable.ic_image_icon)

        when {
            video.url?.trim().equals("settings", ignoreCase = true) -> {
                thumbnailImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_settings_icon)
                )
            }
            video.url?.trim().equals("refresh", ignoreCase = true) -> {
                thumbnailImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_refresh_icon)
                )
            }
            !video.thumbnailUrl.isNullOrBlank() -> {
                thumbnailImage.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(fragment)
                    .load(video.thumbnailUrl)
                    .transform(FitCenter())
                    .placeholder(placeholderIcon)
                    .error(placeholderIcon)
                    .into(thumbnailImage)
            }
            else -> {
                thumbnailImage.setImageDrawable(placeholderIcon)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val thumbnailImage = viewHolder.view.findViewById<ImageView>(R.id.thumbnail_image)
        Glide.with(fragment).clear(thumbnailImage)
        thumbnailImage.setImageDrawable(null)
    }
}