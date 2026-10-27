package com.example.varcamera.usbtest

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/**
 * One screen: instructions, a status line, the camera picture when frames arrive, the
 * live log, and START TEST / SEND LOG. The test starts by itself once a camera with a
 * video interface is plugged in and permission is granted.
 */
class MainActivity : Activity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.varcamera.usbtest.USB_PERMISSION"
        private const val CAMERA_REQUEST = 1
        private const val CHECK_INTERVAL_MS = 1000L
        private const val MAX_SCREEN_LINES = 400
        private const val CLASS_VIDEO = 14
        private const val SUBCLASS_VIDEOSTREAMING = 2
    }

    private lateinit var statusView: TextView
    private lateinit var previewView: ImageView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var log: TestLog

    private val handler = Handler(Looper.getMainLooper())
    private val previewDecoder = Executors.newSingleThreadExecutor()
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val screenLines = ArrayDeque<String>()

    private var probe: UvcProbe? = null
    private var testThread: Thread? = null
    /** One automatic run per camera connection; START TEST repeats it. */
    private var autoTestDone = false
    private var permissionPending = false
    private var permissionRefused = false
    private var cameraPermissionAsked = false
    private var lastUsbList = ""
    private var summary = "No test finished yet."
    @Volatile private var previewBusy = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            permissionPending = false
            val camera = findCamera()
            val granted = camera != null && usbManager.hasPermission(camera)
            log.log("USB permission " + if (granted) "granted" else "refused")
            if (!granted) {
                permissionRefused = true
                setStatus("Permission was refused. Tap START TEST to ask again.")
            }
        }
    }

    private val watcher = object : Runnable {
        override fun run() {
            checkUsb()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        log = TestLog(File(getExternalFilesDir(null) ?: filesDir, "logs")) { line -> runOnUiThread { showLine(line) } }
        logEnvironment()
        ContextCompat.registerReceiver(
            this, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setStatus("Connect the camera to this phone.")
    }

    override fun onResume() {
        super.onResume()
        handler.post(watcher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watcher)
    }

    override fun onDestroy() {
        probe?.stop()
        runCatching { unregisterReceiver(permissionReceiver) }
        previewDecoder.shutdown()
        super.onDestroy()
    }

    private fun checkUsb() {
        if (testThread?.isAlive == true) return
        val devices = usbManager.deviceList.values.toList()
        val list = devices.joinToString { "%04x:%04x %s".format(it.vendorId, it.productId, it.productName ?: "") }
            .ifEmpty { "none" }
        if (list != lastUsbList) {
            lastUsbList = list
            log.log("USB devices: $list")
            devices.forEach { device -> describe(device).forEach { log.log(it) } }
            logAudioInputs()
        }

        val camera = findCamera()
        if (camera == null) {
            autoTestDone = false
            permissionRefused = false
            setStatus(
                if (devices.isEmpty()) "Connect the camera to this phone."
                else "A USB device is connected, but it sends no video.\n" +
                    "On the camera: MENU > Setup > USB > USB Connection Mode > USB Streaming"
            )
            return
        }
        // Without the camera permission Android refuses USB access to a camera at once,
        // without even showing the question.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!cameraPermissionAsked) {
                cameraPermissionAsked = true
                log.log("Asking camera permission (needed before USB access to a camera)")
                setStatus("Tap Allow so VAR USB Test may use the camera.")
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            }
            return
        }
        if (!usbManager.hasPermission(camera)) {
            if (!permissionRefused) askPermission(camera)
            return
        }
        if (!autoTestDone) {
            autoTestDone = true
            startTest(camera)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        log.log("Camera permission " + if (granted) "granted" else "refused")
        if (!granted) setStatus("Camera permission was refused. Tap START TEST to ask again.")
    }

    private fun findCamera(): UsbDevice? = usbManager.deviceList.values.firstOrNull { device ->
        (0 until device.interfaceCount).any {
            val itf = device.getInterface(it)
            itf.interfaceClass == CLASS_VIDEO && itf.interfaceSubclass == SUBCLASS_VIDEOSTREAMING
        }
    }

    private fun askPermission(camera: UsbDevice) {
        if (permissionPending) return
        permissionPending = true
        setStatus("Tap OK so VAR USB Test may use the camera.")
        log.log("Asking USB permission")
        // Permission is read back with hasPermission, so the broadcast needs no extras.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        usbManager.requestPermission(
            camera, PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        )
    }

    private fun onStartPressed() {
        if (testThread?.isAlive == true) {
            Toast.makeText(this, "The test is still running", Toast.LENGTH_SHORT).show()
            return
        }
        val camera = findCamera()
        if (camera == null) {
            Toast.makeText(this, "No camera with video found", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionAsked = false
            autoTestDone = false
            return // checkUsb asks within a second
        }
        if (!usbManager.hasPermission(camera)) {
            permissionRefused = false
            permissionPending = false
            autoTestDone = false
            askPermission(camera)
            return
        }
        autoTestDone = true
        startTest(camera)
    }

    private fun startTest(camera: UsbDevice) {
        previewView.setImageDrawable(null)
        setStatus("Testing... keep the camera connected (about 30 seconds).")
        val p = UvcProbe(usbManager, camera, log, object : UvcProbe.Ui {
            override fun status(text: String) = runOnUiThread { setStatus(text) }
            override fun preview(jpeg: ByteArray) = showPreview(jpeg)
            override fun finished(summary: String) = runOnUiThread {
                this@MainActivity.summary = summary
                setStatus("$summary\n\nDone. Tap SEND LOG and send the file.")
            }
        })
        probe = p
        testThread = Thread({ p.run() }, "usb-test").apply { start() }
    }

    private fun shareLog() {
        log.log("Log shared")
        val uri = FileProvider.getUriForFile(this, "$packageName.logs", log.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "VAR USB Test log")
            putExtra(Intent.EXTRA_TEXT, "VAR USB Test: $summary")
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Send log"))
    }

    private fun logEnvironment() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        log.section("VAR USB Test $version")
        log.log("Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} " +
            "(SDK ${Build.VERSION.SDK_INT}), kernel ${System.getProperty("os.version")}, ABI ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    /** The camera's sound shows up as a USB audio input; the VAR app records from it. */
    private fun logAudioInputs() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { input ->
            log.log("Audio input: type ${input.type} '${input.productName}', rates " +
                input.sampleRates.joinToString("/").ifEmpty { "any" } + ", channels " +
                input.channelCounts.joinToString("/").ifEmpty { "any" })
        }
    }

    /** What Android itself reports; needs no permission, so it works even if the test cannot. */
    private fun describe(device: UsbDevice): List<String> {
        val lines = mutableListOf("  %04x:%04x '%s' by '%s', class %d/%d".format(
            device.vendorId, device.productId, device.productName ?: "", device.manufacturerName ?: "",
            device.deviceClass, device.deviceSubclass
        ))
        for (k in 0 until device.interfaceCount) {
            val itf = device.getInterface(k)
            val endpoints = (0 until itf.endpointCount).joinToString { e ->
                val ep = itf.getEndpoint(e)
                val kind = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                    else -> "control"
                }
                "0x%02x %s %s max %d".format(
                    ep.address, if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT", kind, ep.maxPacketSize
                )
            }
            lines += "    interface %d alt %d class %d/%d: %s".format(
                itf.id, itf.alternateSetting, itf.interfaceClass, itf.interfaceSubclass, endpoints.ifEmpty { "no endpoints" }
            )
        }
        return lines
    }

    private fun showPreview(jpeg: ByteArray) {
        if (previewBusy) return
        previewBusy = true
        val accepted = runCatching {
            previewDecoder.execute {
                val bitmap = runCatching {
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 4 })
                }.getOrNull()
                runOnUiThread { if (bitmap != null) previewView.setImageBitmap(bitmap) }
                previewBusy = false
            }
        }.isSuccess
        if (!accepted) previewBusy = false
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun showLine(line: String) {
        screenLines.addLast(line)
        while (screenLines.size > MAX_SCREEN_LINES) screenLines.removeFirst()
        logView.text = screenLines.joinToString("\n")
        logScroll.post { logScroll.scrollTo(0, logView.height) }
    }

    private fun buildUi() {
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(16, 20, 24))
        }
        root.addView(TextView(this).apply {
            text = "VAR USB Test"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "1. Camera: MENU > Setup > USB > USB Connection Mode > USB Streaming\n" +
                "2. Connect the camera to this phone and tap OK when asked\n" +
                "3. Wait until the test says Done (about 30 seconds)\n" +
                "4. Tap SEND LOG and send the file"
            textSize = 13f
            setTextColor(Color.rgb(170, 180, 190))
            setPadding(0, dp(6), 0, dp(6))
        })
        statusView = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.rgb(255, 214, 102))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(statusView)
        previewView = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(previewView, LinearLayout.LayoutParams(MATCH_PARENT, dp(170)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        buttons.addView(Button(this).apply {
            text = "START TEST"
            setOnClickListener { onStartPressed() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "SEND LOG"
            setOnClickListener { shareLog() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(buttons)

        logView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(200, 205, 210))
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
