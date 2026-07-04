package com.mmhw.csvtv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

class CardPresenter(private val fragment: Fragment) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_compact_card, parent, false)

        val titleText = view.findViewById<TextView>(R.id.title_text)
        val infoLayout = view.findViewById<View>(R.id.info_layout)

        view.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus ->
                val video = getTag(R.id.title_text) as? Video
                val hasAnyBadge = infoLayout.getTag(R.id.info_layout) == true
                android.util.Log.d("CardPresenter", "onFocusChange: title=${video?.title}, hasFocus=$hasFocus, hasAnyBadge=$hasAnyBadge, visibility=${infoLayout.visibility}")
                titleText.visibility = if (hasFocus) View.VISIBLE else View.GONE
                // Show info layout only if focused and it contains active badges (set during bind)
                infoLayout.visibility = if (hasFocus && hasAnyBadge) View.VISIBLE else View.GONE

                val scrollView = infoLayout as? HorizontalScrollView
                val contentView = scrollView?.getChildAt(0)
                if (scrollView != null && contentView != null) {
                    if (hasFocus && hasAnyBadge) {
                        startTickerAnimation(scrollView, contentView)
                    } else {
                        stopTickerAnimation(scrollView)
                    }
                }
            }
        }

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as? Video ?: return
        val view = viewHolder.view

        val thumbnailImage = view.findViewById<ImageView>(R.id.thumbnail_image)
        val titleText = view.findViewById<TextView>(R.id.title_text)
        val infoLayout = view.findViewById<View>(R.id.info_layout)
        val resolutionBadge = view.findViewById<TextView>(R.id.resolution_badge)
        val formatBadge = view.findViewById<TextView>(R.id.format_badge)
        val pingBadge = view.findViewById<TextView>(R.id.ping_badge)
        val audioBadge = view.findViewById<TextView>(R.id.audio_badge)
        val channelsBadge = view.findViewById<TextView>(R.id.channels_badge)
        val sourceBadge = view.findViewById<TextView>(R.id.source_badge)

        val context = fragment.requireContext()
        val placeholderIcon = ContextCompat.getDrawable(context, R.drawable.ic_image_icon)
        val noThumbIcon = run {
            val filterHdrId = context.resources.getIdentifier("ic_filter_hdr", "drawable", context.packageName)
            val resId = if (filterHdrId != 0) filterHdrId else R.drawable.ic_image_icon
            getLargerIcon(context, resId, 1.5f) ?: ContextCompat.getDrawable(context, resId)
        }

        // Reset default states
        view.setTag(R.id.title_text, video)
        view.alpha = 1.0f
        thumbnailImage.colorFilter = null
        infoLayout.setTag(R.id.info_layout, false)
        val scrollView = infoLayout as? HorizontalScrollView
        if (scrollView != null) {
            stopTickerAnimation(scrollView)
        }

        val urlTrimmed = video.url?.trim() ?: ""

        // Handle settings, refresh, update & browser special cards
        if (urlTrimmed.equals("settings", ignoreCase = true) || 
            urlTrimmed.equals("refresh", ignoreCase = true) || 
            urlTrimmed.equals("update", ignoreCase = true) ||
            urlTrimmed.equals("browser", ignoreCase = true)) {
            titleText.text = video.title
            resolutionBadge.visibility = View.GONE
            formatBadge.visibility = View.GONE
            pingBadge.visibility = View.GONE
            audioBadge.visibility = View.GONE
            channelsBadge.visibility = View.GONE
            sourceBadge.visibility = View.GONE
            infoLayout.visibility = View.GONE
 
            val iconDrawable = when {
                urlTrimmed.equals("settings", ignoreCase = true) -> R.drawable.ic_settings_icon
                urlTrimmed.equals("refresh", ignoreCase = true) -> R.drawable.ic_refresh_icon
                urlTrimmed.equals("update", ignoreCase = true) -> R.drawable.ic_update
                else -> R.drawable.ic_desktop  // browser card icon
            }
            val icon = getLargerIcon(context, iconDrawable, 1.5f) ?: ContextCompat.getDrawable(context, iconDrawable)
            thumbnailImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            thumbnailImage.setImageDrawable(icon)
            return
        }

        // Apply visual states based on validation status
        if (video.isValid == false) {
            view.alpha = 0.4f
            titleText.text = "⚠️ " + video.title
        } else {
            titleText.text = video.title
        }

        // Populate badges if online and data is available
        val showDetails = Utils.isShowSourceDetailsEnabled(context)
        if (video.isValid == true && showDetails) {
            var hasAnyBadge = false

            val host = Utils.getDomainName(video.url)
            if (!host.isNullOrBlank()) {
                sourceBadge.text = host
                sourceBadge.visibility = View.VISIBLE
                hasAnyBadge = true
            } else {
                sourceBadge.visibility = View.GONE
            }

            // Show audio badge if audio-only, otherwise resolution badge
            if (video.isAudioOnly == true) {
                audioBadge.text = "AUDIO"
                audioBadge.visibility = View.VISIBLE
                resolutionBadge.visibility = View.GONE
                hasAnyBadge = true
            } else {
                audioBadge.visibility = View.GONE
                if (!video.resolution.isNullOrBlank()) {
                    resolutionBadge.text = video.resolution
                    resolutionBadge.visibility = View.VISIBLE
                    hasAnyBadge = true
                } else {
                    resolutionBadge.visibility = View.GONE
                }
            }

            if (!video.videoFormat.isNullOrBlank()) {
                formatBadge.text = video.videoFormat
                formatBadge.visibility = View.VISIBLE
                hasAnyBadge = true
            } else {
                formatBadge.visibility = View.GONE
            }

            if (video.pingMs != null) {
                pingBadge.text = "${video.pingMs}ms"
                pingBadge.visibility = View.VISIBLE
                hasAnyBadge = true
            } else {
                pingBadge.visibility = View.GONE
            }

            if (!video.audioChannels.isNullOrBlank()) {
                channelsBadge.text = video.audioChannels
                channelsBadge.visibility = View.VISIBLE
                hasAnyBadge = true
            } else {
                channelsBadge.visibility = View.GONE
            }

            infoLayout.setTag(R.id.info_layout, hasAnyBadge)
        } else {
            resolutionBadge.visibility = View.GONE
            formatBadge.visibility = View.GONE
            pingBadge.visibility = View.GONE
            audioBadge.visibility = View.GONE
            channelsBadge.visibility = View.GONE
            sourceBadge.visibility = View.GONE
        }

        // Set initial visibility to avoid flicker
        val initialFocus = view.isFocused || view.hasFocus()
        titleText.visibility = if (initialFocus) View.VISIBLE else View.GONE
        val hasAnyBadge = infoLayout.getTag(R.id.info_layout) == true
        infoLayout.visibility = if (initialFocus && hasAnyBadge) View.VISIBLE else View.GONE

        // Post-layout pass check to handle focus transitions correctly during item updates
        view.post {
            val isFocused = view.isFocused || view.hasFocus()
            titleText.visibility = if (isFocused) View.VISIBLE else View.GONE
            infoLayout.visibility = if (isFocused && hasAnyBadge) View.VISIBLE else View.GONE
            if (isFocused && hasAnyBadge && scrollView != null) {
                val contentView = scrollView.getChildAt(0)
                if (contentView != null) {
                    startTickerAnimation(scrollView, contentView)
                }
            }
        }

        // Bind thumbnail image
        if (!video.thumbnailUrl.isNullOrBlank()) {
            // Start with CENTER_INSIDE so the placeholder icon displays at proper (not oversized/cropped) size
            thumbnailImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            Glide.with(fragment)
                .load(video.thumbnailUrl)
                .transform(FitCenter())
                .placeholder(placeholderIcon)
                .error(noThumbIcon)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        thumbnailImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        thumbnailImage.scaleType = ImageView.ScaleType.CENTER_CROP
                        return false
                    }
                })
                .into(thumbnailImage)
        } else {
            // Use Filter HDR icon if supported (for no-thumbnail cards), centered
            thumbnailImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            thumbnailImage.setImageDrawable(noThumbIcon)
        }
        android.util.Log.d("CardPresenter", "onBindViewHolder: title=${video.title}, isValid=${video.isValid}, tag=${infoLayout.getTag(R.id.info_layout)}")
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val view = viewHolder.view
        val thumbnailImage = view.findViewById<ImageView>(R.id.thumbnail_image)
        Glide.with(fragment).clear(thumbnailImage)
        thumbnailImage.setImageDrawable(null)

        val infoLayout = view.findViewById<View>(R.id.info_layout)
        val scrollView = infoLayout as? HorizontalScrollView
        if (scrollView != null) {
            stopTickerAnimation(scrollView)
        }
    }

    private fun startTickerAnimation(scrollView: HorizontalScrollView, contentView: View) {
        stopTickerAnimation(scrollView)
        scrollView.post {
            val scrollRange = contentView.width - scrollView.width
            if (scrollRange > 0) {
                val animator = ValueAnimator.ofInt(0, scrollRange).apply {
                    duration = (scrollRange * 25L).coerceAtLeast(1500L)
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { animation ->
                        scrollView.scrollX = animation.animatedValue as Int
                    }
                }
                animator.start()
                scrollView.setTag(R.id.resolution_badge, animator)
            }
        }
    }

    private fun stopTickerAnimation(scrollView: HorizontalScrollView) {
        val animator = scrollView.getTag(R.id.resolution_badge) as? ValueAnimator
        animator?.cancel()
        scrollView.setTag(R.id.resolution_badge, null)
        scrollView.scrollX = 0
    }

    private fun getLargerIcon(context: Context, resId: Int, scale: Float = 1.8f): Drawable? {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return drawable
        val w = (drawable.intrinsicWidth * scale).toInt()
        val h = (drawable.intrinsicHeight * scale).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return BitmapDrawable(context.resources, bitmap)
    }
}