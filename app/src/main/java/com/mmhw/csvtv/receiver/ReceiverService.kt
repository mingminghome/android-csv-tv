package com.mmhw.csvtv.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mmhw.csvtv.R

class ReceiverService : Service(), NativeReceiver.Listener {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireLocks()
        NativeReceiver.addListener(this)
        CastCrypto.apply(this)
        val name = ReceiverId.displayName(this)
        val uuid = ReceiverId.uuid(this)
        val rc = if (NativeReceiver.available) {
            NativeReceiver.nativeStart(name, uuid, "")
        } else {
            -2
        }
        Log.i(TAG, "nativeStart=$rc available=${NativeReceiver.available}")
        if (rc != 0) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        NativeReceiver.removeListener(this)
        if (NativeReceiver.available) {
            NativeReceiver.nativeStop()
        }
        multicastLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onReceiverEvent(event: ReceiverNativeEvent) {
        if (event.kind == NativeReceiver.EVENT_PLAY && event.uri.isNotBlank()) {
            val launch = Intent(this, ReceiveActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra(ReceiveActivity.EXTRA_URI, event.uri)
                putExtra(ReceiveActivity.EXTRA_MIME, event.mime)
                putExtra(ReceiveActivity.EXTRA_TITLE, event.title)
                putExtra(ReceiveActivity.EXTRA_MEDIA_KIND, event.mediaKind)
            }
            startActivity(launch)
        }
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("csvtv-dlna")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "csvtv:receiver").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startInForeground() {
        val channelId = "csvtv_receive"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "Receive", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ReceiveActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_receive)
            .setContentTitle(ReceiverId.displayName(this))
            .setContentText(getString(R.string.receiver_ready_hint))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFY_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun pendingImmutable(): Int {
        return if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val NOTIFY_ID = 47
        const val ACTION_STOP = "com.mmhw.csvtv.receiver.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }

        fun uuid(context: Context): String = ReceiverId.uuid(context)
    }
}
