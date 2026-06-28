package com.mmhw.csvtv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.animation.ValueAnimator
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
        val infoLayout = view.findViewById<View>(R.id.info_layout)

        view.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus ->
                titleText.visibility = if (hasFocus) View.VISIBLE else View.GONE
                // Show info layout only if focused and it contains active badges (set during bind)
                infoLayout.visibility = if (hasFocus && infoLayout.tag == true) View.VISIBLE else View.GONE

                val scrollView = infoLayout as? HorizontalScrollView
                val contentView = scrollView?.getChildAt(0)
                if (scrollView != null && contentView != null) {
                    if (hasFocus && infoLayout.tag == true) {
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

        // Reset default states
        view.alpha = 1.0f
        thumbnailImage.colorFilter = null
        infoLayout.tag = false // Tag tracks if there are visible badges to show on focus
        val scrollView = infoLayout as? HorizontalScrollView
        if (scrollView != null) {
            stopTickerAnimation(scrollView)
        }

        val urlTrimmed = video.url?.trim() ?: ""

        // Handle settings & refresh special cards
        if (urlTrimmed.equals("settings", ignoreCase = true) || urlTrimmed.equals("refresh", ignoreCase = true)) {
            titleText.text = video.title
            resolutionBadge.visibility = View.GONE
            formatBadge.visibility = View.GONE
            pingBadge.visibility = View.GONE
            audioBadge.visibility = View.GONE
            channelsBadge.visibility = View.GONE
            sourceBadge.visibility = View.GONE
            infoLayout.visibility = View.GONE

            if (urlTrimmed.equals("settings", ignoreCase = true)) {
                thumbnailImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_settings_icon)
                )
            } else {
                thumbnailImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_refresh_icon)
                )
            }
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
        if (video.isValid == true) {
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

            infoLayout.tag = hasAnyBadge
            infoLayout.visibility = if (view.hasFocus() && hasAnyBadge) View.VISIBLE else View.GONE
            if (view.hasFocus() && hasAnyBadge && scrollView != null) {
                val contentView = scrollView.getChildAt(0)
                if (contentView != null) {
                    startTickerAnimation(scrollView, contentView)
                }
            }
        } else {
            resolutionBadge.visibility = View.GONE
            formatBadge.visibility = View.GONE
            pingBadge.visibility = View.GONE
            audioBadge.visibility = View.GONE
            channelsBadge.visibility = View.GONE
            sourceBadge.visibility = View.GONE
            infoLayout.visibility = View.GONE
        }

        // Bind thumbnail image
        if (!video.thumbnailUrl.isNullOrBlank()) {
            thumbnailImage.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(fragment)
                .load(video.thumbnailUrl)
                .transform(FitCenter())
                .placeholder(placeholderIcon)
                .error(placeholderIcon)
                .into(thumbnailImage)
        } else {
            thumbnailImage.setImageDrawable(placeholderIcon)
        }
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
                scrollView.tag = animator
            }
        }
    }

    private fun stopTickerAnimation(scrollView: HorizontalScrollView) {
        val animator = scrollView.tag as? ValueAnimator
        animator?.cancel()
        scrollView.tag = null
        scrollView.scrollX = 0
    }
}