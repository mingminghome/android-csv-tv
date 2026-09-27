package com.mmhw.csvtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Android TV launcher asks apps to populate the default home-screen channel
 * after install via [TvContractCompat.ACTION_INITIALIZE_PROGRAMS].
 */
class InitializeProgramsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TvContractCompat.ACTION_INITIALIZE_PROGRAMS) return

        val pending = goAsync()
        val appContext = context.applicationContext
        val sheetLink = appContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString("sheet_link", null)

        if (sheetLink.isNullOrBlank()) {
            try {
                HomeScreenPublisher.publishBlocking(appContext, emptyList())
            } catch (e: Exception) {
                Log.e("InitializePrograms", "Failed to clear home channel", e)
            } finally {
                pending.finish()
            }
            return
        }

        Utils.fetchSheetData(appContext, sheetLink) { videos, error ->
            try {
                if (error != null || videos.isEmpty()) {
                    Log.d("InitializePrograms", "No catalog to publish: error=$error")
                    HomeScreenPublisher.publishBlocking(appContext, emptyList())
                } else {
                    HomeScreenPublisher.publishBlocking(appContext, videos)
                }
            } catch (e: Exception) {
                Log.e("InitializePrograms", "Failed to initialize home channel", e)
            } finally {
                pending.finish()
            }
        }
    }
}
