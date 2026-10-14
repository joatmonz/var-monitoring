package com.example.varcamera

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread

/**
 * Diagnostic: talks UVC directly over Android's USB API, bypassing libuvc entirely.
 * Probe/commit then read the streaming endpoint, reporting how many bytes and
 * end-of-frame markers actually arrive.
 *
 * adb shell am start -n com.example.varcamera/.UvcBulkTestActivity --ei frame 6 --ei interval 333333
 */
class UvcBulkTestActivity : Activity() {

    companion object {
        private const val TAG = "UVCBULK"
        private const val ACTION_PERM = "com.example.varcamera.USB_PERM"

        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val VS_PROBE_CONTROL = 0x0100
        private const val VS_COMMIT_CONTROL = 0x0200

        private const val RT_SET = 0x21
        private const val RT_GET = 0xA1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val formatIndex = intent.getIntExtra("format", 1)
        val frameIndex = intent.getIntExtra("frame", 6)
        val frameInterval = intent.getIntExtra("interval", 333333)
        val seconds = intent.getIntExtra("secs", 5)

        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == 14 && dev.getInterface(it).interfaceSubclass == 2
            }
        }
        if (device == null) {
            Log.e(TAG, "No UVC streaming device found")
            finish()
            return
        }

        if (usb.hasPermission(device)) {
            thread { runTest(usb, device, formatIndex, frameIndex, frameInterval, seconds) }
        } else {
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    unregisterReceiver(this)
                    if (usb.hasPermission(device)) {
                        thread { runTest(usb, device, formatIndex, frameIndex, frameInterval, seconds) }
                    } else {
                        Log.e(TAG, "USB permission denied")
                    }
                }
            }, IntentFilter(ACTION_PERM))
            usb.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_PERM).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    private fun runTest(
        usb: UsbManager,
        device: UsbDevice,
        formatIndex: Int,
        frameIndex: Int,
        frameInterval: Int,
        seconds: Int
    ) {
        Log.i(TAG, "=== UVC bulk test: format=$formatIndex frame=$frameIndex interval=$frameInterval ===")

        var vs: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass == 14 && itf.interfaceSubclass == 2) { vs = itf; break }
        }
        if (vs == null) { Log.e(TAG, "No VideoStreaming interface"); return }

        var ep: UsbEndpoint? = null
        for (i in 0 until vs.endpointCount) {
            val e = vs.getEndpoint(i)
            if (e.direction == UsbConstants.USB_DIR_IN) { ep = e; break }
        }
        if (ep == null) { Log.e(TAG, "No IN endpoint on VS interface"); return }
        Log.i(TAG, "VS iface=${vs.id} alt=${vs.alternateSetting} ep=0x%02x type=%d maxPacket=%d"
            .format(ep.address, ep.type, ep.maxPacketSize))

        val conn: UsbDeviceConnection = usb.openDevice(device) ?: run {
            Log.e(TAG, "openDevice failed"); return
        }

        try {
            if (!conn.claimInterface(vs, true)) { Log.e(TAG, "claimInterface failed"); return }

            val probe = ByteArray(26)
            probe[0] = 0x01 // bmHint: keep dwFrameInterval
            probe[2] = formatIndex.toByte()
            probe[3] = frameIndex.toByte()
            for (k in 0..3) probe[4 + k] = ((frameInterval shr (8 * k)) and 0xff).toByte()

            val ifaceNum = vs.id

            var r = conn.controlTransfer(RT_SET, SET_CUR, VS_PROBE_CONTROL, ifaceNum, probe, probe.size, 2000)
            Log.i(TAG, "SET_CUR(PROBE) -> $r")

            val negotiated = ByteArray(26)
            r = conn.controlTransfer(RT_GET, GET_CUR, VS_PROBE_CONTROL, ifaceNum, negotiated, negotiated.size, 2000)
            Log.i(TAG, "GET_CUR(PROBE) -> $r  ${negotiated.joinToString(" ") { "%02x".format(it) }}")
            if (r < 26) { Log.e(TAG, "probe negotiation failed"); return }

            fun le32(off: Int) = (negotiated[off].toLong() and 0xff) or
                ((negotiated[off + 1].toLong() and 0xff) shl 8) or
                ((negotiated[off + 2].toLong() and 0xff) shl 16) or
                ((negotiated[off + 3].toLong() and 0xff) shl 24)

            Log.i(TAG, "negotiated: formatIdx=${negotiated[2]} frameIdx=${negotiated[3]} " +
                "interval=${le32(4)} maxVideoFrameSize=${le32(18)} maxPayloadTransferSize=${le32(22)}")

            r = conn.controlTransfer(RT_SET, SET_CUR, VS_COMMIT_CONTROL, ifaceNum, negotiated, negotiated.size, 2000)
            Log.i(TAG, "SET_CUR(COMMIT) -> $r")
            if (r < 0) { Log.e(TAG, "commit failed"); return }

            val payloadSize = le32(22).toInt().coerceIn(512, 16384)
            val chunk = ByteArray(payloadSize)
            val frame = java.io.ByteArrayOutputStream(le32(18).toInt().coerceAtLeast(64 * 1024))

            var totalBytes = 0L
            var reads = 0
            var errors = 0
            var goodFrames = 0
            var badFrames = 0
            var frameBytes = 0L
            var savedFrame = false
            val kept = ArrayDeque<ByteArray>()
            val start = System.currentTimeMillis()
            val deadline = start + seconds * 1000L

            while (System.currentTimeMillis() < deadline) {
                val n = conn.bulkTransfer(ep, chunk, chunk.size, 1000)
                if (n < 0) {
                    errors++
                    if (errors <= 5) Log.w(TAG, "bulkTransfer -> $n")
                    continue
                }
                reads++
                totalBytes += n
                if (n < 2) continue

                val headerLen = chunk[0].toInt() and 0xff
                val info = chunk[1].toInt() and 0xff
                if (headerLen in 2..n) frame.write(chunk, headerLen, n - headerLen)

                if (info and 0x02 != 0) {
                    val jpeg = frame.toByteArray()
                    frame.reset()
                    val validJpeg = jpeg.size > 4 &&
                        (jpeg[0].toInt() and 0xff) == 0xFF && (jpeg[1].toInt() and 0xff) == 0xD8 &&
                        (jpeg[jpeg.size - 2].toInt() and 0xff) == 0xFF && (jpeg[jpeg.size - 1].toInt() and 0xff) == 0xD9
                    if (validJpeg) {
                        goodFrames++
                        frameBytes += jpeg.size
                        kept.addLast(jpeg)
                        if (kept.size > 60) kept.removeFirst()
                        if (!savedFrame && goodFrames >= 30) {
                            savedFrame = true
                            val out = java.io.File(getExternalFilesDir(null), "test_frame.jpg")
                            out.writeBytes(jpeg)
                            Log.i(TAG, "saved first JPEG: ${out.absolutePath} (${jpeg.size} bytes)")
                        }
                    } else if (jpeg.isNotEmpty()) {
                        badFrames++
                        if (badFrames <= 3) Log.w(TAG, "malformed frame size=${jpeg.size}")
                    }
                }
            }

            val elapsed = (System.currentTimeMillis() - start) / 1000.0
            val fps = goodFrames / elapsed
            val avgFrame = if (goodFrames > 0) frameBytes / goodFrames else 0
            Log.i(TAG, "=== RESULT: reads=$reads bytes=$totalBytes goodFrames=$goodFrames " +
                "badFrames=$badFrames errors=$errors fps=%.1f avgFrameBytes=$avgFrame ===".format(fps))
            benchmarkDecode(kept.toList())
        } catch (e: Exception) {
            Log.e(TAG, "test failed: ${e.message}", e)
        } finally {
            conn.releaseInterface(vs)
            conn.close()
        }
    }

    /** Decode cost per frame for the given sample size, single- or multi-threaded. */
    private fun benchmarkDecode(frames: List<ByteArray>) {
        if (frames.isEmpty()) return

        fun run(sampleSize: Int, threads: Int): Double {
            val start = System.nanoTime()
            val workers = (0 until threads).map { worker ->
                Thread {
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        inSampleSize = sampleSize
                        inMutable = true
                    }
                    var reuse: android.graphics.Bitmap? = null
                    for (i in worker until frames.size step threads) {
                        options.inBitmap = reuse
                        reuse = android.graphics.BitmapFactory.decodeByteArray(frames[i], 0, frames[i].size, options)
                    }
                }.apply { start() }
            }
            workers.forEach { it.join() }
            return (System.nanoTime() - start) / 1e6 / frames.size
        }

        run(1, 1) // warm-up
        Log.i(TAG, "=== DECODE ms/frame over ${frames.size} frames: " +
            "full=%.1f full2threads=%.1f full3threads=%.1f half=%.1f eighth=%.1f ===".format(
                run(1, 1), run(1, 2), run(1, 3), run(2, 1), run(8, 1)))
    }
}
