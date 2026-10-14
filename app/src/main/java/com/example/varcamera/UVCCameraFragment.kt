package com.example.varcamera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.varcamera.diag.DiagnosticLog
import com.example.varcamera.recording.AudioRingBuffer
import com.example.varcamera.recording.ClipStore
import com.example.varcamera.recording.FrameRingBuffer
import com.example.varcamera.review.MjpegClip
import com.example.varcamera.uvc.UvcCamera
import com.example.varcamera.uvc.UvcPreviewView
import kotlinx.coroutines.delay

/**
 * Live monitoring screen.
 *
 * Streams the HDMI capture card through the project's own UVC driver
 * ([UvcCamera]) straight into a [UvcPreviewView].
 */
class UVCCameraFragment : androidx.fragment.app.Fragment() {

    companion object {
        private const val TAG = "UVCCameraFragment"
        private const val ACTION_USB_PERMISSION = "com.example.varcamera.USB_PERMISSION"
        private const val TARGET_WIDTH = 1920
        private const val TARGET_HEIGHT = 1080
        private const val TARGET_FPS = 50

        /** While no card is streaming, look for one this often, so a slow card needs no RETRY. */
        private const val RECONNECT_INTERVAL_MS = 3000L

        /** How often the log gets a status line (battery, heat, USB devices, fps). */
        private const val HEARTBEAT_INTERVAL_MS = 10_000L

        fun newInstance(): UVCCameraFragment {
            return UVCCameraFragment()
        }
    }

    // Camera connection state
    private val isCameraConnected = mutableStateOf(false)
    private val isRecording = mutableStateOf(false)
    private val statusMessage = mutableStateOf("Waiting for capture card...")
    private val overlayTitle = mutableStateOf("No Capture Card")
    private val isPreparingClip = mutableStateOf(false)

    // Recording: the last 30 seconds, saved as a clip when REVIEW is pressed.
    private lateinit var clipStore: ClipStore
    private lateinit var ringBuffer: FrameRingBuffer
    private lateinit var audioBuffer: AudioRingBuffer

    private lateinit var previewView: UvcPreviewView
    private val camera = UvcCamera()
    private var usbManager: UsbManager? = null
    private var streamWidth = TARGET_WIDTH
    private var streamHeight = TARGET_HEIGHT

    // Live only when the card streams AND the picture is not a blank "no signal" frame.
    private var hasUsbSignal = false
    private var hasPicture = false

    /** Whether the operator wants recording on; survives signal dropouts. */
    private var recordingRequested = false

    /** A permission dialog is on screen; asking again would stack dialogs. */
    private var permissionPending = false

    /** Last connect outcome logged, so the 3-second retries do not flood the log. */
    private var lastConnectOutcome: String? = null
    private var lastFps = 0
    private var lastState = ""

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastHeartbeatAt = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (!camera.isStreaming) connectIfPossible()
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                lastHeartbeatAt = now
                logHeartbeat()
            }
            handler.postDelayed(this, RECONNECT_INTERVAL_MS)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            @Suppress("DEPRECATION")
            val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    permissionPending = false
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    DiagnosticLog.log("Usb", "permission ${if (granted) "granted" else "DENIED"} for ${describe(device)}")
                    connectIfPossible()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    DiagnosticLog.log("Usb", "attached ${describe(device)}")
                    connectIfPossible()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    DiagnosticLog.log("Usb", "detached ${describe(device)}")
                    camera.stop()
                    onDisconnected("Capture card unplugged")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
        clipStore = ClipStore(requireContext())
        ringBuffer = FrameRingBuffer(clipStore.liveBufferDir)
        audioBuffer = AudioRingBuffer(requireContext())

        previewView = view.findViewById(R.id.preview_surface)
        previewView.onStats = { fps, decodeMs ->
            Log.d(TAG, "preview: $fps fps, decode ${decodeMs}ms")
            lastFps = fps
            if (isCameraConnected.value) {
                statusMessage.value = "${streamWidth}x$streamHeight · $fps fps"
            }
        }
        previewView.onBlankChanged = { isBlank ->
            activity?.runOnUiThread {
                DiagnosticLog.log("Preview", if (isBlank) "picture blank (no-signal screen)" else "picture present")
                hasPicture = !isBlank
                updateSignalState()
            }
        }

        androidx.core.content.ContextCompat.registerReceiver(
            requireContext(),
            usbReceiver,
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )

        val composeView = view.findViewById<ComposeView>(R.id.compose_overlay)
        composeView.setContent {
            VarTheme {
                LiveMonitorOverlay(
                    isCameraConnected = isCameraConnected.value,
                    isRecording = isRecording.value,
                    statusMessage = statusMessage.value,
                    overlayTitle = overlayTitle.value,
                    isPreparingClip = isPreparingClip.value,
                    bufferedSeconds = ringBuffer.bufferedSeconds(),
                    freeSpaceMb = clipStore.freeSpaceMB(),
                    storageLow = clipStore.isStorageLow(),
                    canReview = ringBuffer.hasFrames() || clipStore.hasClips(),
                    audioLevel = { audioBuffer.level() },
                    onToggleRecording = { toggleRecording() },
                    onSwitchToReview = { openReview() },
                    onShareLog = { shareLog() },
                    onRetryConnect = {
                        Toast.makeText(context, "Scanning USB devices...", Toast.LENGTH_SHORT).show()
                        connectIfPossible()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLog.log("App", "live screen resumed")
        lastConnectOutcome = null
        connectIfPossible()
        audioBuffer.start()
        handler.post(watchdog)
    }

    override fun onPause() {
        super.onPause()
        DiagnosticLog.log("App", "live screen paused; stopping the stream")
        handler.removeCallbacks(watchdog)
        camera.stop()
        audioBuffer.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        camera.stop()
        ringBuffer.release()
        // recordingRequested survives, so recording restarts once the live view is back.
        isRecording.value = false
        runCatching { requireContext().unregisterReceiver(usbReceiver) }
    }

    /**
     * While the review screen covers this one, streaming and buffering carry on but the
     * live preview is hidden: its surface would fight the review surface for z-order,
     * and its decoding would take CPU the 1080p review decoders need.
     */
    fun setReviewShowing(showing: Boolean) {
        if (!::previewView.isInitialized) return
        previewView.visibility = if (showing) View.INVISIBLE else View.VISIBLE
    }

    /** Finds the capture card, asks for USB permission if needed, and starts streaming. */
    private fun connectIfPossible() {
        if (camera.isStreaming) return
        val manager = usbManager ?: return

        val device = UvcCamera.findDevice(manager)
        if (device == null) {
            logConnectOutcome("no UVC device; USB devices: ${usbDeviceList(manager)}")
            onDisconnected("No capture card found - check the cable")
            return
        }
        if (!manager.hasPermission(device)) {
            statusMessage.value = "Waiting for USB permission..."
            if (permissionPending) return
            permissionPending = true
            DiagnosticLog.log("Usb", "asking permission for ${describe(device)}")
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(requireContext().packageName)
            manager.requestPermission(
                device,
                PendingIntent.getBroadcast(requireContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
            return
        }
        startStream(manager, device)
    }

    private fun startStream(manager: UsbManager, device: UsbDevice) {
        statusMessage.value = "Connecting..."
        DiagnosticLog.log("Usb", "starting stream on ${describe(device)}")
        previewView.clear()
        val error = camera.start(
            manager, device, TARGET_WIDTH, TARGET_HEIGHT, TARGET_FPS,
            object : UvcCamera.Listener {
                override fun onStreamStarted(width: Int, height: Int, fps: Int) {
                    lastConnectOutcome = null
                    streamWidth = width
                    streamHeight = height
                    activity?.runOnUiThread {
                        hasUsbSignal = true
                        // Stays offline until a moving picture proves the camera is on.
                        hasPicture = false
                        updateSignalState()
                    }
                }

                override fun onFrame(jpeg: ByteArray) {
                    previewView.submitFrame(jpeg)
                    ringBuffer.append(jpeg)
                }

                override fun onSignalChanged(hasSignal: Boolean) {
                    activity?.runOnUiThread {
                        hasUsbSignal = hasSignal
                        updateSignalState()
                    }
                }

                override fun onStreamStopped(reason: String?) {
                    DiagnosticLog.log("Uvc", "stream stopped: ${reason ?: "no reason given"}")
                    activity?.runOnUiThread { onDisconnected(reason ?: "Video stream stopped") }
                }
            }
        )
        if (error != null) {
            logConnectOutcome("start failed: $error")
            onDisconnected(error)
        }
    }

    /**
     * A card that is plugged in but fed by a powered-off camera still looks "connected",
     * so the live state needs both a running stream and an actual picture.
     */
    private fun updateSignalState() {
        val live = camera.isStreaming && hasUsbSignal && hasPicture
        val wasLive = isCameraConnected.value
        isCameraConnected.value = live

        val state = when {
            live -> "LIVE"
            camera.isStreaming -> "NO SIGNAL (usb frames: $hasUsbSignal, picture: $hasPicture)"
            else -> "NO CARD"
        }
        if (state != lastState) {
            lastState = state
            DiagnosticLog.log("State", state)
        }

        if (live) {
            statusMessage.value = "${streamWidth}x$streamHeight"
            if (!wasLive && recordingRequested) startRecording()
            return
        }

        if (camera.isStreaming) {
            overlayTitle.value = "No Signal"
            statusMessage.value = "Camera is powered off or HDMI is disconnected"
        } else {
            overlayTitle.value = "No Capture Card"
            statusMessage.value = "Waiting for capture card..."
        }

        if (wasLive) {
            // Keep only real footage; recording resumes, with a fresh buffer, on signal return.
            if (ringBuffer.isRecording) {
                ringBuffer.stop()
                isRecording.value = false
            }
            previewView.clear()
        }
    }

    private fun onDisconnected(message: String) {
        hasUsbSignal = false
        hasPicture = false
        isCameraConnected.value = false
        overlayTitle.value = "No Capture Card"
        statusMessage.value = message
        stopRecording()
        if (::previewView.isInitialized) previewView.clear()
    }

    private fun toggleRecording() {
        if (isRecording.value) {
            recordingRequested = false
            stopRecording()
        } else if (!isCameraConnected.value) {
            Toast.makeText(context, "No video signal - turn the camera on first", Toast.LENGTH_SHORT).show()
        } else {
            recordingRequested = true
            startRecording()
        }
    }

    private fun startRecording() {
        if (ringBuffer.isRecording) return
        if (clipStore.isStorageLow()) {
            Toast.makeText(
                context,
                "Storage almost full (${clipStore.freeSpaceMB()} MB) - free up space",
                Toast.LENGTH_LONG
            ).show()
        }
        ringBuffer.start()
        isRecording.value = true
        DiagnosticLog.log("Rec", "recording started")
    }

    private fun stopRecording() {
        if (ringBuffer.isRecording) DiagnosticLog.log("Rec", "recording stopped")
        ringBuffer.stop()
        isRecording.value = false
    }

    /**
     * Saves the last 30 seconds as a clip and opens the review screen on it; recording
     * continues underneath. With nothing new since the last clip it just opens the
     * clips saved earlier.
     */
    private fun openReview() {
        if (isPreparingClip.value) return
        val host = activity as? MainActivity ?: return

        if (!ringBuffer.hasUnsavedFrames()) {
            host.switchToReview(clipStore.list(), clipStore)
            return
        }

        isPreparingClip.value = true
        Thread {
            val saved = ringBuffer.saveClip(clipStore.newClipDir())
            DiagnosticLog.log("Rec", "REVIEW pressed; clip ${saved?.name ?: "NOT saved"}")
            saved?.let { saveClipAudio(it) }
            clipStore.enforceLimits()
            val clips = clipStore.list()
            activity?.runOnUiThread {
                isPreparingClip.value = false
                if (isAdded) host.switchToReview(clips, clipStore)
            }
        }.start()
    }

    private fun logConnectOutcome(outcome: String) {
        if (outcome == lastConnectOutcome) return
        lastConnectOutcome = outcome
        DiagnosticLog.log("Usb", outcome)
    }

    /** One status line: enough to tell a flat battery or an overheated tablet from a bad cable. */
    private fun logHeartbeat() {
        val context = context ?: return
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 /
                it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        } ?: -1
        val plugged = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temperature = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) {
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus.toString()
        } else "n/a"
        DiagnosticLog.log(
            "Status",
            "state=$lastState fps=${if (isCameraConnected.value) lastFps else 0} rec=${ringBuffer.isRecording} " +
                "battery=$level%${if (plugged) " charging" else ""} temp=${temperature}C thermal=$thermal " +
                "free=${clipStore.freeSpaceMB()}MB usb=[${usbManager?.let { usbDeviceList(it) } ?: "?"}]"
        )
    }

    private fun usbDeviceList(manager: UsbManager): String =
        manager.deviceList.values.joinToString { describe(it) }.ifEmpty { "none" }

    private fun describe(device: UsbDevice?): String =
        if (device == null) "unknown device"
        else "%04x:%04x %s".format(device.vendorId, device.productId, device.productName ?: "")

    /** Sends the recent log files through the share sheet (WhatsApp, e-mail, Drive...). */
    private fun shareLog() {
        val context = requireContext()
        DiagnosticLog.log("App", "log shared")
        val files = DiagnosticLog.recentFiles()
        if (files.isEmpty()) {
            Toast.makeText(context, "No log yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList(files.map {
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.logs", it)
        })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/plain")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Send log"))
    }

    /** Cuts the buffered sound to exactly the span of the frames just saved. */
    private fun saveClipAudio(clipDir: java.io.File) {
        val clip = MjpegClip.open(clipDir) ?: return
        try {
            if (!audioBuffer.saveClip(clipDir, clip.firstCaptureNanos, clip.lastCaptureNanos)) {
                DiagnosticLog.log("Audio", "no sound captured for ${clipDir.name}")
            }
        } catch (e: Exception) {
            DiagnosticLog.log("Audio", "saving sound failed: ${e.message}")
        } finally {
            clip.close()
        }
    }
}

// ============================================================================
// COMPOSE UI OVERLAY
// ============================================================================

@Composable
fun LiveMonitorOverlay(
    isCameraConnected: Boolean,
    isRecording: Boolean,
    statusMessage: String,
    overlayTitle: String,
    isPreparingClip: Boolean,
    bufferedSeconds: Int,
    freeSpaceMb: Long,
    storageLow: Boolean,
    canReview: Boolean,
    audioLevel: () -> Float?,
    onShareLog: () -> Unit,
    onToggleRecording: () -> Unit,
    onSwitchToReview: () -> Unit,
    onRetryConnect: (() -> Unit)? = null
) {
    var recordingSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isRecording) {
        recordingSeconds = 0
        while (isRecording) {
            delay(1000)
            recordingSeconds++
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // "No Camera" overlay, kept in the space between the top and bottom bars so it
        // never runs into the buttons on a short phone screen.
        if (!isCameraConnected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp, bottom = 100.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideocamOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = overlayTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusMessage,
                        color = Color.Yellow,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (onRetryConnect != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        VARButton(text = "RETRY", icon = Icons.Rounded.Refresh, onClick = onRetryConnect)
                    }
                }
            }
        }

        // ===== TOP BAR =====
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            if (isCameraConnected) Color.Green else Color.Red,
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isCameraConnected) "UVC LIVE" else "OFFLINE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (isCameraConnected) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = statusMessage,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                AudioMeter(audioLevel)
            }

            if (isRecording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var visible by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(500)
                            visible = !visible
                        }
                    }
                    if (visible) {
                        Box(modifier = Modifier.size(18.dp).background(VarColors.Live, CircleShape))
                    } else {
                        Spacer(modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "REC ${formatTime(recordingSeconds)}",
                        color = VarColors.Live,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BUFFER ${formatTime(bufferedSeconds)} · $freeSpaceMb MB free",
                    color = if (storageLow) Color(0xFFFF5252) else Color.White.copy(alpha = 0.7f),
                    fontWeight = if (storageLow) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(VarColors.Surface)
                        .clickable { onShareLog() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BugReport,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("LOG", color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Forgetting REC loses the incident, so a live picture that is not being
        // recorded nags until it is: a banner, and a REC button that beats.
        var forgotToRecord by remember { mutableStateOf(false) }
        LaunchedEffect(isCameraConnected, isRecording) {
            forgotToRecord = false
            if (isCameraConnected && !isRecording) {
                delay(3000)
                forgotToRecord = true
            }
        }
        val pulse by rememberInfiniteTransition(label = "recPulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "recPulseValue"
        )

        if (forgotToRecord) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(VarColors.Range.copy(alpha = 0.75f + 0.25f * pulse))
                    .clickable { onToggleRecording() }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = VarColors.OnAccent,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "NOT RECORDING  ·  tap here or REC to start",
                    color = VarColors.OnAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }

        // While recording, a red frame round the whole screen says so from across the pitch.
        if (isRecording) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(6.dp, VarColors.Live.copy(alpha = 0.55f + 0.45f * pulse))
            )
        }

        // ===== BOTTOM CONTROL BAR =====
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.scale(if (forgotToRecord) 1f + 0.08f * pulse else 1f)) {
                VARButton(
                    text = if (isRecording) "STOP" else "REC",
                    icon = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                    tone = if (isRecording) ButtonTone.Neutral else ButtonTone.Live,
                    onClick = onToggleRecording,
                    minWidth = 120.dp
                )
            }

            VARButton(
                text = "REVIEW",
                icon = Icons.Rounded.SlowMotionVideo,
                tone = ButtonTone.Primary,
                onClick = onSwitchToReview,
                enabled = canReview && !isPreparingClip,
                minWidth = 140.dp
            )
        }

        if (isPreparingClip) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color(0xFF00D4FF), strokeWidth = 4.dp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Preparing clip...",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }
    }
}

/** HDMI sound level, so the operator can see the camera's sound is arriving before a match. */
@Composable
private fun AudioMeter(level: () -> Float?) {
    var value by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            value = level()
            delay(100)
        }
    }

    val current = value
    if (current == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.MicOff,
                contentDescription = null,
                tint = VarColors.Range,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("NO AUDIO", color = VarColors.Range, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(METER_SEGMENTS) { i ->
                val lit = current * METER_SEGMENTS > i
                val color = when {
                    i >= METER_SEGMENTS - 1 -> Color(0xFFFF5252)
                    i >= METER_SEGMENTS - 3 -> Color(0xFFFFEB3B)
                    else -> Color(0xFF4CAF50)
                }
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = 14.dp)
                        .background(if (lit) color else Color.White.copy(alpha = 0.15f), RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

private const val METER_SEGMENTS = 12
