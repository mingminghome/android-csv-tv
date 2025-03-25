package com.mmhw.csvtv

import android.util.Log
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
    private val TAG = "CardPresenter"

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.layout_compact_card, parent, false)

        val thumbnailImage = view.findViewById<ImageView>(R.id.thumbnail_image)
        val titleText = view.findViewById<TextView>(R.id.title_text)

        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnFocusChangeListener { v, hasFocus ->
            titleText.visibility = if (hasFocus) View.VISIBLE else View.GONE
        }

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as Video
        val view = viewHolder.view

        val thumbnailImage = view.findViewById<ImageView>(R.id.thumbnail_image)
        val titleText = view.findViewById<TextView>(R.id.title_text)
        val settingsIcon = ContextCompat.getDrawable(fragment.requireContext(), R.drawable.ic_settings_icon)
        val placeholderIcon = ContextCompat.getDrawable(fragment.requireContext(), R.drawable.ic_image_icon)

        thumbnailImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
        titleText.text = video.title
        Log.d(TAG, "Binding video: url='${video.url}', title='${video.title}'")

        if (video.url?.trim()?.equals("settings", ignoreCase = true) == true) {
            // For the settings item, use the settingsIcon from MainFragment
            thumbnailImage.setImageDrawable(settingsIcon)

            Log.d(TAG, "Set settings icon for Settings item")
        } else if (!video.thumbnailUrl.isNullOrBlank()) {
            Log.d(TAG, "Loading thumbnail for ${video.title}: ${video.thumbnailUrl}")
            // Use centerCrop for thumbnails
            thumbnailImage.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(fragment)
                .load(video.thumbnailUrl)
                .transform(FitCenter())
                .placeholder(placeholderIcon)
                .error(placeholderIcon)
                .into(thumbnailImage)
        } else {
            Log.w(TAG, "No thumbnail URL for ${video.title}, using placeholder")
            // Use centerInside for placeholder
            thumbnailImage.setImageDrawable(placeholderIcon)

            Log.d(TAG, "Set placeholderIcon icon for no thumbnail item")
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val view = viewHolder.view
        val thumbnailImage = view.findViewById<ImageView>(R.id.thumbnail_image)
        Glide.with(fragment).clear(thumbnailImage)
        thumbnailImage.setImageDrawable(null)
        // Reset scaleType to default (centerCrop) for reuse
        thumbnailImage.scaleType = ImageView.ScaleType.CENTER_CROP
    }
}