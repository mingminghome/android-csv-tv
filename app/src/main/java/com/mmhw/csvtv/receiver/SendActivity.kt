package com.mmhw.csvtv.receiver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mmhw.csvtv.AppChrome
import com.mmhw.csvtv.AppToolbar
import com.mmhw.csvtv.OrientationPolicy
import com.mmhw.csvtv.R
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Sender: pick a receiver first, then a photo, video, audio, or this screen.
 * Share arrives with media already chosen, so that step is skipped.
 */
class SendActivity : AppCompatActivity(), SenderService.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var hint: TextView
    private lateinit var status: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var castTypes: View
    private lateinit var pickButton: Button
    private lateinit var screenButton: Button
    private lateinit var stopButton: Button

    private var multicastLock: WifiManager.MulticastLock? = null
    private var pickedUri: Uri? = null
    private var pickedUris: List<Uri> = emptyList()
    private var pickedMime: String = "image/jpeg"
    private var pickedTitle: String = "Media"
    private var devices: List<RendererDevice> = emptyList()
    private var selected: RendererDevice? = null
    private var cacheFile: File? = null
    /** Share already has the file or URL, so skip the cast-type step. */
    private var sharePayload = false
    private var screenCast = false

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        restoreOrientation()
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        screenCast = false
        applyShared(uris, null)
        selected?.let { sendTo(it) }
    }

    private val screenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        restoreOrientation()
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            status.text = getString(R.string.send_cast_denied)
            return@registerForActivityResult
        }
        startScreenCast(result.resultCode, result.data!!)
    }

    private val askMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        launchScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppChrome.prepare(this)
        bindViews()
        CastCrypto.apply(this)
        ShareShortcut.publish(this)

        restoreState(savedInstanceState)
        acquireMulticast()
        handleShareIntent(intent, fromCreate = true)
        updateHint()
        updateCastTypes()
        if (!NativeSender.available) {
            status.text = getString(R.string.receiver_missing_lib)
        } else {
            scan()
        }
        refreshSessionUi()
    }

    override fun onStart() {
        super.onStart()
        SenderService.addListener(this)
    }

    override fun onStop() {
        SenderService.removeListener(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        restoreOrientation()
        refreshSessionUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindViews()
        updateHint()
        updateCastTypes()
        renderDevices()
        refreshSessionUi()
    }

    private fun bindViews() {
        setContentView(R.layout.activity_send)
        AppChrome.padSystemBars(this)

        hint = findViewById(R.id.toolbar_subtitle)
        status = findViewById(R.id.send_status)
        deviceList = findViewById(R.id.send_device_list)
        castTypes = findViewById(R.id.send_cast_types)
        stopButton = findViewById(R.id.send_stop)
        AppToolbar.bind(
            activity = this,
            title = getString(R.string.send_title),
            subtitle = getString(R.string.send_hint),
            onBack = { finish() }
        )
        CastOverflow.attach(this)

        pickButton = findViewById(R.id.send_pick)
        screenButton = findViewById(R.id.send_cast_screen)
        pickButton.setOnClickListener { openPicker() }
        screenButton.setOnClickListener { requestScreenCast() }
        findViewById<Button>(R.id.send_scan).setOnClickListener { scan() }
        stopButton.setOnClickListener { stopPlay() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent, fromCreate = false)
        updateHint()
        updateCastTypes()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("share", sharePayload)
        outState.putString("picked", pickedUri?.toString())
        outState.putStringArray("picked_list", pickedUris.map { it.toString() }.toTypedArray())
        outState.putString("mime", pickedMime)
        outState.putString("title", pickedTitle)
        selected?.let { device ->
            outState.putString("sel_name", device.name)
            outState.putString("sel_uuid", device.uuid)
            outState.putString("sel_control", device.controlUrl)
            outState.putBoolean("sel_castify", device.castify)
        }
    }

    override fun onDestroy() {
        multicastLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onSenderStopped() {
        main.post {
            if (isDestroyed) return@post
            screenCast = false
            stopButton.visibility = View.GONE
            status.text = getString(R.string.send_stopped)
            updateHint()
            updateCastTypes()
        }
    }

    private fun restoreState(saved: Bundle?) {
        if (saved == null) return
        sharePayload = saved.getBoolean("share", false)
        saved.getString("picked")?.let { pickedUri = Uri.parse(it) }
        pickedUris = saved.getStringArray("picked_list")?.map { Uri.parse(it) }.orEmpty()
        pickedMime = saved.getString("mime") ?: pickedMime
        pickedTitle = saved.getString("title") ?: pickedTitle
        val control = saved.getString("sel_control")
        if (!control.isNullOrBlank()) {
            selected = RendererDevice(
                saved.getString("sel_name") ?: "Receiver",
                saved.getString("sel_uuid").orEmpty(),
                control,
                saved.getBoolean("sel_castify", false)
            )
            SenderService.setTarget(selected!!.name, control)
        }
    }

    private fun unlockOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun restoreOrientation() {
        OrientationPolicy.apply(this)
    }

    private fun openPicker() {
        if (selected == null) {
            Toast.makeText(this, getString(R.string.send_need_receiver), Toast.LENGTH_LONG).show()
            return
        }
        unlockOrientation()
        pickMedia.launch(arrayOf("image/*", "video/*", "audio/*"))
    }

    private fun handleShareIntent(intent: Intent?, fromCreate: Boolean) {
        if (intent == null) return
        val action = intent.action ?: return
        val streams = shareUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        val sharedUrl = SendMedia.extractHttpUrl(text)
        when {
            streams.size > 1 -> {
                applyShared(streams, intent.type)
                sharePayload = true
            }
            streams.size == 1 -> {
                applyShared(streams, intent.type)
                sharePayload = true
            }
            sharedUrl != null && SendMedia.isWebPage(sharedUrl) -> {
                applyPicked(Uri.parse(sharedUrl), "text/html")
                sharePayload = true
            }
            sharedUrl != null && SendMedia.isDirectHttpUrl(sharedUrl) -> {
                applyPicked(Uri.parse(sharedUrl), intent.type)
                sharePayload = true
            }
            (action == Intent.ACTION_SEND || action == Intent.ACTION_SENDTO) &&
                SendMedia.isDirectHttpUrl(text) -> {
                applyPicked(Uri.parse(text.trim()), intent.type)
                sharePayload = true
            }
        }
        if (fromCreate && sharePayload) {
            updateCastTypes()
        }
    }

    private fun shareUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            list?.forEach { if (it != null) out.add(it) }
        } else if (intent.action == Intent.ACTION_SEND) {
            val one = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (one != null) out.add(one)
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    private fun tryTakePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun applyShared(uris: List<Uri>, typeHint: String?) {
        val clean = uris.filterNotNull().take(Album.MAX_ITEMS)
        if (clean.isEmpty()) return
        clean.forEach { tryTakePermission(it) }
        pickedUris = clean
        applyPicked(clean.first(), typeHint)
        if (clean.size > 1) {
            pickedTitle = getString(R.string.send_collection_title, clean.size)
            pickedMime = Album.MIME
        }
        updateHint()
    }

    private fun applyPicked(uri: Uri, typeHint: String?) {
        pickedUri = uri
        if (pickedUris.isEmpty()) {
            pickedUris = listOf(uri)
        }
        val raw = uri.toString()
        pickedMime = SendMedia.mimeOf(
            typeHint ?: contentResolver.getType(uri),
            raw
        )
        pickedTitle = SendMedia.titleOf(raw)
        updateHint()
    }

    private fun hasShareMedia(): Boolean {
        return pickedUris.size > 1 || pickedUri != null
    }

    private fun updateHint() {
        val device = selected
        hint.text = when {
            sharePayload && pickedUris.size > 1 ->
                getString(R.string.send_hint_share, pickedTitle)
            sharePayload && pickedUri != null ->
                getString(R.string.send_hint_share, pickedTitle)
            device != null ->
                getString(R.string.send_hint_choose_type, device.name)
            else -> getString(R.string.send_hint)
        }
    }

    private fun updateCastTypes() {
        if (sharePayload) {
            castTypes.visibility = View.GONE
            return
        }
        castTypes.visibility = View.VISIBLE
        val on = selected != null
        pickButton.isEnabled = on
        screenButton.isEnabled = on
        val alpha = if (on) 1f else 0.4f
        pickButton.alpha = alpha
        screenButton.alpha = alpha
    }

    private fun acquireMulticast() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("csvtv-send")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun scan() {
        if (!NativeSender.available) return
        status.text = getString(R.string.send_scanning)
        deviceList.removeAllViews()
        io.execute {
            val json = try {
                NativeSender.nativeSearch(3000) ?: "[]"
            } catch (e: Exception) {
                Log.w(TAG, "search", e)
                "[]"
            }
            val found = parseDevices(json)
            main.post {
                devices = found
                if (selected != null && found.none { it.controlUrl == selected?.controlUrl }) {
                    val keep = selected
                    if (keep != null) {
                        devices = listOf(keep) + found
                    }
                }
                renderDevices()
                status.text = if (found.isEmpty()) {
                    getString(R.string.send_none)
                } else if (sharePayload) {
                    getString(R.string.send_found_share, found.size)
                } else {
                    getString(R.string.send_found, found.size)
                }
            }
        }
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        devices.forEach { device ->
            val density = resources.displayMetrics.density
            val chosen = device.controlUrl == selected?.controlUrl
            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.action_height)
                ).also { it.bottomMargin = (6 * density).toInt() }
                text = ReceiverId.listLabel(device.name, device.uuid)
                isAllCaps = false
                isSelected = chosen
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setBackgroundResource(R.drawable.button_focus_bg)
                setTextColor(ContextCompat.getColorStateList(this@SendActivity, R.color.button_text_color))
                setPadding((14 * density).toInt(), 0, (12 * density).toInt(), 0)
                setOnClickListener { onDeviceTapped(device) }
            }
            deviceList.addView(btn)
        }
    }

    private fun onDeviceTapped(device: RendererDevice) {
        selectDevice(device, sendIfReady = true)
    }

    private fun selectDevice(device: RendererDevice, sendIfReady: Boolean) {
        selected = device
        SenderService.setTarget(device.name, device.controlUrl)
        renderDevices()
        updateHint()
        updateCastTypes()
        if (!sendIfReady) return
        when {
            sharePayload && hasShareMedia() -> sendTo(device)
            screenCast && ScreenCastService.isRunning -> sendTo(device)
        }
    }

    private fun requestScreenCast() {
        if (selected == null) {
            Toast.makeText(this, getString(R.string.send_need_receiver), Toast.LENGTH_LONG).show()
            return
        }
        if (!ScreenCastService.supported()) {
            Toast.makeText(this, getString(R.string.send_cast_need_android), Toast.LENGTH_LONG).show()
            return
        }
        if (!NativeSender.available) {
            status.text = getString(R.string.receiver_missing_lib)
            return
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchScreenCapture()
    }

    private fun launchScreenCapture() {
        unlockOrientation()
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapture.launch(mgr.createScreenCaptureIntent())
    }

    private fun startScreenCast(resultCode: Int, data: Intent) {
        val target = selected
        if (target == null) {
            status.text = getString(R.string.send_need_receiver)
            return
        }
        status.text = getString(R.string.send_preparing, getString(R.string.send_cast_title))
        ScreenCastService.start(this, resultCode, data)
        io.execute {
            val url = ScreenCastService.awaitUrl(12_000)
            main.post {
                if (url.isNullOrBlank()) {
                    screenCast = false
                    status.text = getString(R.string.send_cast_unsupported)
                    return@post
                }
                screenCast = true
                pickedUri = null
                stopButton.visibility = View.VISIBLE
                playUrl(target, url, getString(R.string.send_cast_title), "video/mp2t")
            }
        }
    }

    private fun refreshSessionUi() {
        val sending = SenderService.isRunning || ScreenCastService.isRunning
        if (!sending) {
            stopButton.visibility = View.GONE
            return
        }
        stopButton.visibility = View.VISIBLE
        screenCast = ScreenCastService.isRunning
        val name = SenderService.deviceName ?: selected?.name
        if (name != null) {
            status.text = if (screenCast) {
                getString(R.string.send_cast_live, name)
            } else {
                getString(R.string.send_sent, name)
            }
        }
    }

    private fun sendTo(device: RendererDevice) {
        selected = device
        SenderService.setTarget(device.name, device.controlUrl)
        if (screenCast && ScreenCastService.isRunning) {
            val url = ScreenCastService.publicUrl
            if (url.isNullOrBlank()) {
                status.text = getString(R.string.send_cast_unsupported)
                return
            }
            if (blockedByEncryption(device)) return
            playUrl(device, url, getString(R.string.send_cast_title), "video/mp2t")
            return
        }
        if (pickedUris.size > 1) {
            sendAlbum(device, pickedUris)
            return
        }
        AlbumServer.stop()
        val uri = pickedUri ?: return
        if (!NativeSender.available) return
        val raw = uri.toString()
        if (blockedByEncryption(device)) return
        status.text = getString(R.string.send_sending, device.name)
        val mime = pickedMime
        val title = pickedTitle
        io.execute {
            try {
                val mediaUrl = resolvePlayUrl(uri, mime)
                if (mediaUrl.isNullOrBlank()) {
                    main.post { status.text = getString(R.string.send_serve_fail) }
                    return@execute
                }
                playUrlOnThread(device, mediaUrl, title, mime)
            } catch (e: Exception) {
                Log.w(TAG, "send", e)
                main.post { status.text = e.message ?: getString(R.string.send_play_fail) }
            }
        }
    }

    private fun sendAlbum(device: RendererDevice, uris: List<Uri>) {
        if (!NativeSender.available) return
        if (blockedByEncryption(device)) return
        val title = pickedTitle
        status.text = getString(R.string.send_preparing, title)
        io.execute {
            try {
                val dir = File(cacheDir, "album-${System.currentTimeMillis()}")
                if (!dir.mkdirs()) {
                    main.post { status.text = getString(R.string.send_serve_fail) }
                    return@execute
                }
                val hosted = ArrayList<AlbumServer.Hosted>()
                uris.take(Album.MAX_ITEMS).forEachIndexed { i, uri ->
                    val mime = SendMedia.mimeOf(contentResolver.getType(uri), uri.toString())
                    val ext = SendMedia.extensionOf(mime, uri.toString())
                    val file = File(dir, "$i.$ext")
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: return@forEachIndexed
                    hosted.add(
                        AlbumServer.Hosted(
                            file = file,
                            mime = mime,
                            title = SendMedia.titleOf(uri.toString(), "Item ${i + 1}"),
                            kind = Album.kindOf(mime)
                        )
                    )
                    if (i % 3 == 0) {
                        val n = i + 1
                        main.post {
                            status.text = getString(R.string.send_preparing_n, n, uris.size)
                        }
                    }
                }
                if (hosted.isEmpty()) {
                    dir.deleteRecursively()
                    main.post { status.text = getString(R.string.send_serve_fail) }
                    return@execute
                }
                val ip = LanAddress.ipv4(this)
                val url = AlbumServer.start(ip, title, hosted)
                SenderService.holdDir(dir)
                playUrlOnThread(device, url, title, Album.MIME)
            } catch (e: Exception) {
                Log.w(TAG, "album", e)
                main.post { status.text = e.message ?: getString(R.string.send_play_fail) }
            }
        }
    }

    private fun resolvePlayUrl(uri: Uri, mime: String): String? {
        val raw = uri.toString()
        if (SendMedia.isDirectHttpUrl(raw)) {
            return raw
        }
        main.post { status.text = getString(R.string.send_preparing, pickedTitle) }
        CastCrypto.apply(this)
        val file = copyToCache(uri, mime)
        cacheFile?.delete()
        cacheFile = file
        return NativeSender.nativeServe(file.absolutePath, mime)
    }

    private fun playUrl(device: RendererDevice, mediaUrl: String, title: String, mime: String) {
        selected = device
        status.text = getString(R.string.send_sending, device.name)
        io.execute { playUrlOnThread(device, mediaUrl, title, mime) }
    }

    private fun playUrlOnThread(
        device: RendererDevice,
        mediaUrl: String,
        title: String,
        mime: String
    ) {
        CastCrypto.apply(this)
        if (blockedByEncryption(device)) return
        val rc = try {
            NativeSender.nativePlay(device.controlUrl, mediaUrl, title, mime)
        } catch (e: Exception) {
            Log.w(TAG, "play", e)
            main.post { status.text = e.message ?: getString(R.string.send_play_fail) }
            return
        }
        main.post {
            when (rc) {
                0 -> {
                    SenderService.onPlaying(
                        this,
                        device.name,
                        device.controlUrl,
                        title,
                        screenCast,
                        cacheFile
                    )
                    cacheFile = null
                    status.text = if (screenCast) {
                        getString(R.string.send_cast_live, device.name)
                    } else {
                        getString(R.string.send_sent, device.name)
                    }
                    stopButton.visibility = View.VISIBLE
                }
                -2 -> status.text = getString(R.string.send_play_not_castify, device.name)
                else -> status.text = getString(R.string.send_play_fail)
            }
        }
    }

    private fun blockedByEncryption(device: RendererDevice): Boolean {
        if (CastCryptoPolicy.playAllowed(CastCrypto.enabled(this), device.castify)) return false
        val message = getString(R.string.send_play_not_castify, device.name)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            status.text = message
        } else {
            main.post { status.text = message }
        }
        return true
    }

    private fun stopPlay() {
        screenCast = false
        SenderService.stop(this)
        stopButton.visibility = View.GONE
        status.text = getString(R.string.send_stopped)
        updateHint()
        updateCastTypes()
    }

    private fun copyToCache(uri: Uri, mime: String): File {
        val ext = SendMedia.extensionOf(mime, uri.toString())
        val out = File(cacheDir, "send-${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("Could not read the file")
        return out
    }

    private data class RendererDevice(
        val name: String,
        val uuid: String,
        val controlUrl: String,
        val castify: Boolean
    )

    private fun parseDevices(json: String): List<RendererDevice> {
        val out = ArrayList<RendererDevice>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { "Renderer" }
            val uuid = o.optString("uuid")
            val control = o.optString("controlUrl")
            if (control.isNotBlank()) {
                out.add(RendererDevice(name, uuid, control, o.optBoolean("castify", false)))
            }
        }
        return out
    }

    companion object {
        private const val TAG = "SendActivity"
    }
}
