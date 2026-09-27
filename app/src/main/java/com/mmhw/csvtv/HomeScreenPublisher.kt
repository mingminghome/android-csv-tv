package com.mmhw.csvtv

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Publishes a default Android TV home-screen channel (API 26+) so catalog
 * posters appear on the system launcher.
 *
 * Empty or failed catalogs clear programs so stale cards do not linger.
 */
object HomeScreenPublisher {
    const val SCHEME = "csvtv"
    const val HOST_PLAY = "play"
    const val HOST_HOME = "home"
    const val QUERY_URL = "url"
    const val QUERY_TITLE = "title"

    private const val TAG = "HomeScreenPublisher"
    private const val PREFS = "AppPrefs"
    private const val KEY_CHANNEL_ID = "home_channel_id"
    private const val CHANNEL_INTERNAL_ID = "csvtv_default_channel"

    fun publish(context: Context, videos: List<Video>?) {
        val appContext = context.applicationContext
        Thread {
            try {
                publishBlocking(appContext, videos)
            } catch (e: SecurityException) {
                Log.w(TAG, "TV provider permission denied", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish home-screen channel", e)
            }
        }.start()
    }

    fun publishBlocking(context: Context, videos: List<Video>?) {
        if (!isHomeChannelSupported(context)) {
            Log.d(TAG, "Home-screen channels are not available on this device")
            return
        }

        val channelId = ensureChannel(context)
        if (channelId == -1L) return

        val programsUri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId)
        context.contentResolver.delete(programsUri, null, null)

        val programs = VideoCatalog.homePrograms(videos)
        if (programs.isEmpty()) {
            Log.d(TAG, "Catalog empty or missing — cleared home-screen programs")
            return
        }

        val fallbackPoster = fallbackPosterUri(context)
        programs.forEachIndexed { index, video ->
            val poster = video.thumbnailUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { Uri.parse(it) }
                ?: fallbackPoster
            val weight = programs.size - index
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(video.title)
                .setDescription(video.groupName)
                .setPosterArtUri(poster)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setThumbnailUri(poster)
                .setThumbnailAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(playUri(video))
                .setInternalProviderId(video.url.trim().take(512))
                .setWeight(weight)
                .setBrowsable(true)
                .build()

            try {
                val uri = context.contentResolver.insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    program.toContentValues()
                )
                if (uri == null) {
                    Log.w(TAG, "Insert returned null for ${video.title}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to insert program ${video.title}", e)
            }
        }
        Log.d(TAG, "Published ${programs.size} home-screen programs (featured=${programs.first().title})")
    }

    fun playUri(video: Video): Uri {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_PLAY)
            .appendQueryParameter(QUERY_URL, video.url)
            .appendQueryParameter(QUERY_TITLE, video.title)
            .build()
    }

    fun homeUri(): Uri {
        return Uri.Builder().scheme(SCHEME).authority(HOST_HOME).build()
    }

    fun parsePlayUri(uri: Uri?): Pair<String, String?>? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST_PLAY) return null
        val url = uri.getQueryParameter(QUERY_URL)?.trim().orEmpty()
        if (url.isBlank()) return null
        val title = uri.getQueryParameter(QUERY_TITLE)?.trim()?.takeIf { it.isNotBlank() }
        return url to title
    }

    fun isHomeChannelSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureChannel(context: Context): Long {
        val existing = findExistingChannelId(context)
        if (existing != -1L) {
            persistChannelId(context, existing)
            storeLogo(context, existing)
            TvContractCompat.requestChannelBrowsable(context, existing)
            return existing
        }

        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(context.getString(R.string.app_name))
            .setDescription(context.getString(R.string.home_channel_description))
            .setAppLinkIntentUri(homeUri())
            .setInternalProviderId(CHANNEL_INTERNAL_ID)
            .build()

        val channelUri = context.contentResolver.insert(
            TvContractCompat.Channels.CONTENT_URI,
            channel.toContentValues()
        )
        if (channelUri == null) {
            Log.e(TAG, "Failed to insert home-screen channel")
            return -1L
        }
        val channelId = ContentUris.parseId(channelUri)
        persistChannelId(context, channelId)
        storeLogo(context, channelId)
        TvContractCompat.requestChannelBrowsable(context, channelId)
        Log.d(TAG, "Created home-screen channel id=$channelId")
        return channelId
    }

    private fun findExistingChannelId(context: Context): Long {
        try {
            context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                null,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val channel = Channel.fromCursor(cursor)
                    if (channel.internalProviderId == CHANNEL_INTERNAL_ID) {
                        return channel.id
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query existing channels", e)
        }

        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_CHANNEL_ID, -1L)
        if (stored == -1L) return -1L
        try {
            context.contentResolver.query(
                TvContractCompat.buildChannelUri(stored),
                null,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) return stored
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stored channel id $stored is gone", e)
        }
        return -1L
    }

    private fun persistChannelId(context: Context, channelId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CHANNEL_ID, channelId)
            .apply()
    }

    private fun storeLogo(context: Context, channelId: Long) {
        val logo = channelLogoBitmap(context) ?: return
        try {
            ChannelLogoUtils.storeChannelLogo(context, channelId, logo)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store channel logo", e)
        }
    }

    private fun channelLogoBitmap(context: Context): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val size = 160
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    private fun fallbackPosterUri(context: Context): Uri {
        val resId = if (context.resources.getIdentifier(
                "ic_launcher_banner",
                "mipmap",
                context.packageName
            ) != 0
        ) {
            R.mipmap.ic_launcher_banner
        } else {
            R.mipmap.ic_launcher
        }
        return Uri.parse("android.resource://${context.packageName}/$resId")
    }
}
