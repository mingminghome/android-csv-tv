package com.mmhw.csvtv.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mmhw.csvtv.R
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Keeps a Send session alive in the background and shows a shade
 * notification with Stop (photo, video, audio, web, or screen).
 */
class SenderService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(this)
            return START_NOT_STICKY
        }
        deviceName = intent?.getStringExtra(EXTRA_DEVICE)?.ifBlank { null } ?: deviceName
        controlUrl = intent?.getStringExtra(EXTRA_CONTROL)?.ifBlank { null } ?: controlUrl
        title = intent?.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: title
        screen = intent?.getBooleanExtra(EXTRA_SCREEN, screen) ?: screen
        isRunning = true
        startInForeground()
        acquireWake()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (isRunning) {
            teardownNative()
            isRunning = false
            ScreenCastService.stop(this)
            notifyStopped()
        }
        super.onDestroy()
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "csvtv:sender").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startInForeground() {
        val channelId = "csvtv_send"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.send_title),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SendActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SenderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        val name = deviceName ?: getString(R.string.send_title)
        val body = title ?: getString(R.string.send_title)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.send_notify_title, name))
            .setContentText(getString(R.string.send_notify_text, body))
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.send_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFY_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun pendingImmutable(): Int {
        return if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    }

    interface Listener {
        fun onSenderStopped()
    }

    companion object {
        private const val TAG = "SenderService"
        private const val NOTIFY_ID = 49
        const val ACTION_STOP = "com.mmhw.csvtv.receiver.STOP_SEND"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_CONTROL = "control"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SCREEN = "screen"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var deviceName: String? = null
            private set

        @Volatile
        var controlUrl: String? = null
            private set

        @Volatile
        var title: String? = null
            private set

        @Volatile
        var screen: Boolean = false
            private set

        @Volatile
        private var cacheFile: File? = null

        @Volatile
        private var albumCacheDir: File? = null

        @Volatile
        private var stopping: Boolean = false

        private val listeners = CopyOnWriteArrayList<Listener>()
        private val io = Executors.newSingleThreadExecutor()

        fun addListener(listener: Listener) {
            listeners.add(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        fun setTarget(name: String, control: String) {
            deviceName = name
            controlUrl = control
        }

        fun holdCache(file: File?) {
            if (cacheFile != file) {
                cacheFile?.delete()
            }
            cacheFile = file
        }

        fun holdDir(dir: File?) {
            if (albumCacheDir != dir) {
                albumCacheDir?.deleteRecursively()
            }
            albumCacheDir = dir
        }

        fun onPlaying(
            context: Context,
            name: String,
            control: String,
            playingTitle: String,
            isScreen: Boolean,
            cache: File? = null
        ) {
            deviceName = name
            controlUrl = control
            title = playingTitle
            screen = isScreen
            isRunning = true
            if (cache != null) holdCache(cache)
            if (isScreen) {
                return
            }
            val intent = Intent(context, SenderService::class.java).apply {
                putExtra(EXTRA_DEVICE, name)
                putExtra(EXTRA_CONTROL, control)
                putExtra(EXTRA_TITLE, playingTitle)
                putExtra(EXTRA_SCREEN, false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (stopping) return
            stopping = true
            try {
                teardownNative()
                isRunning = false
                deviceName = null
                controlUrl = null
                title = null
                screen = false
                ScreenCastService.stop(context)
                AlbumServer.stop()
                context.stopService(Intent(context, SenderService::class.java))
                notifyStopped()
            } finally {
                stopping = false
            }
        }

        private fun teardownNative() {
            AlbumServer.stop()
            val control = controlUrl
            val cache = cacheFile
            val dir = albumCacheDir
            cacheFile = null
            albumCacheDir = null
            io.execute {
                try {
                    if (!control.isNullOrBlank() && NativeSender.available) {
                        NativeSender.nativeStopPlay(control)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stop play", e)
                }
                try {
                    if (NativeSender.available) NativeSender.nativeServeStop()
                } catch (e: Exception) {
                    Log.w(TAG, "serve stop", e)
                }
                cache?.delete()
                dir?.deleteRecursively()
            }
        }

        private fun notifyStopped() {
            listeners.forEach { listener ->
                try {
                    listener.onSenderStopped()
                } catch (e: Exception) {
                    Log.w(TAG, "listener", e)
                }
            }
        }
    }
}
