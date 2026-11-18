package com.mmhw.csvtv.receiver

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.mmhw.csvtv.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures this device’s screen (MediaProjection), encodes H.264, muxes MPEG-TS,
 * and serves it over HTTP. Send then plays that URL to a receiver.
 */
class ScreenCastService : Service() {
    private val running = AtomicBoolean(false)
    private var tsServer = LiveTsServer(includeAudio = false)
    private var tsMux = MpegTsMuxer(includeAudio = false)
    private val muxLock = Any()
    private var ptsAnchorNs = 0L
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var codecConfig: ByteArray = ByteArray(0)
    private val firstKeyframe = AtomicBoolean(false)
    @Volatile
    private var lastKeyAu: ByteArray? = null
    @Volatile
    private var lastVideoNs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val t = HandlerThread("csvtv-screencast", Process.THREAD_PRIORITY_DISPLAY)
        t.start()
        worker = t
        handler = Handler(t.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SenderService.stop(this)
            return START_NOT_STICKY
        }
        startInForeground()
        if (running.get()) {
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "missing MediaProjection result")
            urlReady?.countDown()
            stopSelf()
            return START_NOT_STICKY
        }
        running.set(true)
        handler?.post { startCast(code, data) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        publicUrl = null
        urlReady?.countDown()
        val h = handler
        if (h != null) {
            h.post {
                teardown()
                worker?.quitSafely()
            }
        } else {
            teardown()
        }
        super.onDestroy()
    }

    private fun startCast(resultCode: Int, data: Intent) {
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("getMediaProjection returned null")
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, handler)

            acquireWake()
            firstKeyframe.set(false)
            ptsAnchorNs = SystemClock.elapsedRealtimeNanos()
            val audioOn = startAudio(proj)
            tsMux = MpegTsMuxer(includeAudio = audioOn)
            tsServer = LiveTsServer(includeAudio = audioOn)
            tsServer.onClient = { requestKeyframe() }
            val ip = LanAddress.ipv4(this)
            val url = tsServer.start(ip)
            publicUrl = url
            Log.i(TAG, "live $url audio=$audioOn")
            startEncoder(proj)
            requestKeyframe()
            handler?.postDelayed(repeatLastFrame, 100)
            handler?.postDelayed(object : Runnable {
                override fun run() {
                    if (!running.get()) return
                    requestKeyframe()
                    handler?.postDelayed(this, 2_000)
                }
            }, 2_000)
        } catch (e: Exception) {
            Log.e(TAG, "startCast", e)
            publicUrl = null
            urlReady?.countDown()
            stopSelf()
        }
    }

    private fun startEncoder(proj: MediaProjection) {
        val dm = resources.displayMetrics
        var width = dm.widthPixels.coerceAtLeast(2)
        var height = dm.heightPixels.coerceAtLeast(2)
        val maxSide = 1280
        val longSide = maxOf(width, height)
        if (longSide > maxSide) {
            val s = maxSide.toFloat() / longSide
            width = (width * s).toInt()
            height = (height * s).toInt()
        }
        width = align16(width)
        height = align16(height)
        val dpi = dm.densityDpi.coerceAtLeast(160)
        val bitrate = if (maxOf(width, height) >= 1100) 5_000_000 else 3_500_000

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        format.setInteger(
            MediaFormat.KEY_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        )
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        val enc = configureEncoder(format, width, height, bitrate)
        codec = enc
        surface = enc.createInputSurface()
        enc.start()
        try {
            val csd = csdFromFormat(enc.outputFormat)
            if (csd.isNotEmpty()) codecConfig = csd
        } catch (e: Exception) {
            Log.w(TAG, "csd", e)
        }
        startDrain(enc)

        virtualDisplay = proj.createVirtualDisplay(
            "csvtv-screen",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            handler
        )
        Log.i(TAG, "encoder ${width}x$height @$bitrate")
    }

    private fun configureEncoder(
        format: MediaFormat,
        width: Int,
        height: Int,
        bitrate: Int
    ): MediaCodec {
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return enc
        } catch (e: Exception) {
            Log.w(TAG, "encoder configure, retry without extra keys", e)
            try {
                enc.release()
            } catch (_: Exception) {
            }
        }
        val retry = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        retry.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        retry.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        retry.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        retry.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        retry.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        val enc2 = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc2.configure(retry, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return enc2
    }

    private fun startDrain(enc: MediaCodec) {
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (running.get()) {
                val idx = try {
                    enc.dequeueOutputBuffer(info, 100_000)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "drain", e)
                    break
                }
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                if (idx < 0) continue
                try {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val raw = ByteArray(info.size)
                        buf.get(raw)
                        handleEncoded(raw, info)
                    }
                    enc.releaseOutputBuffer(idx, false)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "encoded", e)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }, "csvtv-enc-drain").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleEncoded(raw: ByteArray, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            codecConfig = MpegTsMuxer.toAnnexB(raw)
            return
        }
        val frame = MpegTsMuxer.toAnnexB(raw)
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ||
            MpegTsMuxer.isIdr(frame)
        if (!key && !firstKeyframe.get()) {
            return
        }
        val au = ByteArrayOutputStream(frame.size + codecConfig.size + 16)
        au.write(MpegTsMuxer.audNal())
        if (key && codecConfig.isNotEmpty()) {
            au.write(codecConfig)
        }
        au.write(frame)
        val accessUnit = au.toByteArray()
        if (key) {
            lastKeyAu = accessUnit
        }
        lastVideoNs = SystemClock.elapsedRealtimeNanos()
        val packets = synchronized(muxLock) {
            tsMux.muxVideo(accessUnit, ptsUs(), key)
        }
        tsServer.push(packets, 0, packets.size, keyframe = key)
        if (key && firstKeyframe.compareAndSet(false, true)) {
            urlReady?.countDown()
        }
    }

    private val repeatLastFrame = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val idleNs = SystemClock.elapsedRealtimeNanos() - lastVideoNs
            val au = lastKeyAu
            if (au != null && lastVideoNs > 0L && idleNs > 100_000_000L) {
                val packets = synchronized(muxLock) {
                    tsMux.muxVideo(au, ptsUs(), true)
                }
                tsServer.push(packets, 0, packets.size, keyframe = true)
                lastVideoNs = SystemClock.elapsedRealtimeNanos()
            }
            handler?.postDelayed(this, 100)
        }
    }

    private fun requestKeyframe() {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(params)
        } catch (_: Exception) {
        }
    }

    private fun csdFromFormat(format: MediaFormat): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (key in arrayOf("csd-0", "csd-1")) {
            val bb = try {
                format.getByteBuffer(key)
            } catch (_: Exception) {
                null
            } ?: continue
            val raw = ByteArray(bb.remaining())
            val pos = bb.position()
            bb.get(raw)
            bb.position(pos)
            out.write(MpegTsMuxer.toAnnexB(raw))
        }
        return out.toByteArray()
    }

    private fun ptsUs(): Long {
        return (SystemClock.elapsedRealtimeNanos() - ptsAnchorNs) / 1_000L
    }

    private fun startAudio(proj: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "screen audio skipped: no RECORD_AUDIO")
            return false
        }
        try {
            val capture = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            audioRecord = record
            audioCodec = enc
            record.startRecording()
            audioThread = Thread({ audioLoop(record, enc, sampleRate) }, "csvtv-aac").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "screen audio capture on")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "screen audio", e)
            return false
        }
    }

    private fun audioLoop(record: AudioRecord, enc: MediaCodec, sampleRate: Int) {
        val pcm = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        while (running.get()) {
            val n = try {
                record.read(pcm, 0, pcm.size)
            } catch (_: Exception) {
                break
            }
            if (n > 0) {
                val inIdx = try {
                    enc.dequeueInputBuffer(10_000)
                } catch (_: Exception) {
                    break
                }
                if (inIdx >= 0) {
                    val buf = enc.getInputBuffer(inIdx)
                    buf?.clear()
                    buf?.put(pcm, 0, n)
                    try {
                        enc.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                    } catch (_: Exception) {
                        break
                    }
                    ptsUs += (n / 4) * 1_000_000L / sampleRate
                }
            }
            drainAac(enc, info)
        }
    }

    private fun drainAac(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (running.get()) {
            val idx = try {
                enc.dequeueOutputBuffer(info, 0)
            } catch (_: Exception) {
                return
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (idx < 0) continue
            try {
                val buf = enc.getOutputBuffer(idx)
                if (buf != null && info.size > 0 &&
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val aac = ByteArray(info.size)
                    buf.get(aac)
                    val adts = MpegTsMuxer.adtsFrame(aac)
                    val packets = synchronized(muxLock) {
                        tsMux.muxAudio(adts, ptsUs())
                    }
                    tsServer.push(packets, 0, packets.size, keyframe = false)
                }
                enc.releaseOutputBuffer(idx, false)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "aac", e)
                return
            }
        }
    }

    private fun teardown() {
        running.set(false)
        lastKeyAu = null
        lastVideoNs = 0L
        tsServer.onClient = null
        handler?.removeCallbacks(repeatLastFrame)
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            audioCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            audioCodec?.release()
        } catch (_: Exception) {
        }
        audioCodec = null
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        surface = null
        tsServer.stop()
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        try {
            drainThread?.join(400)
        } catch (_: Exception) {
        }
        try {
            audioThread?.join(400)
        } catch (_: Exception) {
        }
        drainThread = null
        audioThread = null
    }

    private fun acquireWake() {
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "csvtv:screencast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startInForeground() {
        val channelId = "csvtv_screencast"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.send_cast_notify_title),
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
            Intent(this, ScreenCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_desktop)
            .setContentTitle(getString(R.string.send_cast_notify_title))
            .setContentText(getString(R.string.send_cast_notify_text))
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.send_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFY_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
        isRunning = true
    }

    private fun pendingImmutable(): Int {
        return if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    }

    companion object {
        private const val TAG = "ScreenCastService"
        private const val NOTIFY_ID = 48
        const val ACTION_STOP = "com.mmhw.csvtv.receiver.STOP_SCREEN_CAST"
        private const val EXTRA_CODE = "result_code"
        private const val EXTRA_DATA = "result_data"

        @Volatile
        var publicUrl: String? = null
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var urlReady: CountDownLatch? = null

        fun supported(): Boolean = true

        fun start(context: Context, resultCode: Int, data: Intent) {
            urlReady = CountDownLatch(1)
            publicUrl = null
            val intent = Intent(context, ScreenCastService::class.java).apply {
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun awaitUrl(timeoutMs: Long): String? {
            val latch = urlReady ?: return publicUrl
            val ready = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return if (ready) publicUrl else null
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCastService::class.java))
        }

        private fun align16(n: Int): Int {
            val v = n - n % 16
            return if (v < 16) 16 else v
        }
    }
}
