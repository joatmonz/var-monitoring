package com.example.varcamera.uvc

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.varcamera.diag.DiagnosticLog
import java.io.ByteArrayOutputStream

/**
 * Minimal UVC (USB Video Class) driver built directly on Android's USB host API.
 *
 * Exists because libausbc/libuvc never delivers frames from bulk-only capture cards
 * (VideoStreaming interface with a single altsetting), which is what this project's
 * HDMI grabber is. Talking UVC directly avoids that entire dependency.
 *
 * Only MJPEG is supported — uncompressed 720p does not fit in USB 2.0 bandwidth.
 */
class UvcCamera {

    companion object {

        private const val CLASS_VIDEO = 14
        private const val SUBCLASS_VIDEOSTREAMING = 2

        private const val RT_SET = 0x21
        private const val RT_GET = 0xA1
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val VS_PROBE_CONTROL = 0x0100
        private const val VS_COMMIT_CONTROL = 0x0200

        private const val DESC_INTERFACE = 0x04
        private const val DESC_CS_INTERFACE = 0x24
        private const val VS_INPUT_HEADER = 0x01
        private const val VS_FORMAT_MJPEG = 0x06
        private const val VS_FRAME_MJPEG = 0x07

        /** UVC frame intervals are in 100ns units. */
        private const val INTERVAL_UNITS_PER_SECOND = 10_000_000L

        /** No frame for this long means the HDMI source stopped feeding the card. */
        private const val SIGNAL_TIMEOUT_MS = 1500L

        /** While the signal is down, re-commit occasionally so streaming restarts by itself. */
        private const val RECOMMIT_INTERVAL_MS = 4000L

        /**
         * Byte-identical frames for this long mean the card is repeating its last
         * picture. Compared at JPEG level because a live camera re-encodes every
         * frame slightly differently even when the scene is completely still.
         */
        private const val FROZEN_TIMEOUT_MS = 4000L

        /**
         * Bytes per bulk read. A payload can be much bigger (the Sony A7 IV sends a whole
         * frame, 1.3 MB, as one payload) and the phone refuses reads that large, so
         * payloads are rebuilt from several reads.
         */
        private const val READ_BYTES = 16384
        private const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

        fun findDevice(usbManager: UsbManager): UsbDevice? =
            usbManager.deviceList.values.firstOrNull { device ->
                (0 until device.interfaceCount).any { idx ->
                    val itf = device.getInterface(idx)
                    itf.interfaceClass == CLASS_VIDEO && itf.interfaceSubclass == SUBCLASS_VIDEOSTREAMING
                }
            }
    }

    data class FrameSpec(
        val index: Int,
        val width: Int,
        val height: Int,
        val defaultInterval: Int,
        val intervals: List<Int>
    )

    private data class StreamingInfo(
        val interfaceNumber: Int,
        val endpointAddress: Int,
        val mjpegFormatIndex: Int,
        val frames: List<FrameSpec>
    )

    interface Listener {
        fun onStreamStarted(width: Int, height: Int, fps: Int)
        fun onFrame(jpeg: ByteArray)
        /** Frames stopped arriving (HDMI source powered off or unplugged), or resumed. */
        fun onSignalChanged(hasSignal: Boolean)
        fun onStreamStopped(reason: String?)
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var claimed: UsbInterface? = null
    private var committed: ByteArray? = null
    private var committedInterface = 0

    val isStreaming: Boolean get() = running

    fun start(
        usbManager: UsbManager,
        device: UsbDevice,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        listener: Listener
    ): String? {
        if (running) return "already streaming"

        val conn = usbManager.openDevice(device) ?: return "Could not open the USB device".also {
            DiagnosticLog.log("Uvc", "start failed: $it")
        }
        connection = conn

        val info = parseStreamingInfo(conn.rawDescriptors)
            ?: return fail(conn, "This device does not expose an MJPEG video stream")

        val vsInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == info.interfaceNumber && it.endpointCount > 0 }
            ?: return fail(
                conn,
                // No endpoint on the default altsetting means the card streams
                // isochronously, which Android's public USB API cannot read at all.
                if ((0 until device.interfaceCount).any { device.getInterface(it).id == info.interfaceNumber }) {
                    "This capture card uses isochronous streaming, which Android cannot read. " +
                        "Use a bulk-mode capture card (MS2109/MS2130 class)."
                } else {
                    "VideoStreaming interface not found"
                }
            )

        val endpoint: UsbEndpoint = (0 until vsInterface.endpointCount)
            .map { vsInterface.getEndpoint(it) }
            .firstOrNull { it.address == info.endpointAddress }
            ?: (0 until vsInterface.endpointCount)
                .map { vsInterface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            ?: return fail(conn, "Video endpoint not found")

        if (!conn.claimInterface(vsInterface, true)) {
            return fail(conn, "Could not claim the USB interface (another app may be using it)")
        }
        claimed = vsInterface

        val frame = chooseFrame(info.frames, targetWidth, targetHeight)
            ?: return fail(conn, "No usable resolution offered by this device")
        val interval = chooseInterval(frame, targetFps)

        val negotiated = negotiate(conn, info.interfaceNumber, info.mjpegFormatIndex, frame.index, interval)
            ?: return fail(conn, "Format negotiation with the capture card failed")
        committed = negotiated
        committedInterface = info.interfaceNumber

        val payloadSize = le32(negotiated, 22).toInt().coerceIn(512, MAX_PAYLOAD_BYTES)
        val maxFrameSize = le32(negotiated, 18).toInt().coerceAtLeast(64 * 1024)
        val actualFps = (INTERVAL_UNITS_PER_SECOND / le32(negotiated, 4).coerceAtLeast(1)).toInt()

        DiagnosticLog.log("Uvc", "streaming ${frame.width}x${frame.height} @${actualFps}fps " +
            "payload=$payloadSize maxFrame=$maxFrameSize ep=0x%02x".format(endpoint.address))

        running = true
        listener.onStreamStarted(frame.width, frame.height, actualFps)

        thread = Thread({
            var reason: String? = null
            try {
                streamLoop(conn, endpoint, payloadSize, maxFrameSize, listener)
            } catch (e: Exception) {
                reason = e.message
                DiagnosticLog.log("Uvc", "stream loop failed: ${Log.getStackTraceString(e)}")
            } finally {
                running = false
                releaseUsb()
                listener.onStreamStopped(reason)
            }
        }, "uvc-stream").apply { priority = Thread.MAX_PRIORITY; start() }

        return null
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        releaseUsb()
    }

    private fun streamLoop(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        payloadSize: Int,
        maxFrameSize: Int,
        listener: Listener
    ) {
        val chunk = ByteArray(READ_BYTES)
        val payload = ByteArray(maxOf(payloadSize, READ_BYTES) + READ_BYTES)
        var payloadLength = 0
        val assembly = ByteArrayOutputStream(maxFrameSize)
        var lastFrameAt = System.currentTimeMillis()
        var lastChangeAt = lastFrameAt
        var lastSignature = 0L
        var lastRecommitAt = 0L
        var hasSignal = true

        while (running) {
            val got = conn.bulkTransfer(endpoint, chunk, chunk.size, 500)

            val now = System.currentTimeMillis()
            val silent = now - lastFrameAt > SIGNAL_TIMEOUT_MS
            val frozen = now - lastChangeAt > FROZEN_TIMEOUT_MS

            if (hasSignal && (silent || frozen)) {
                hasSignal = false
                assembly.reset()
                DiagnosticLog.log("Uvc", "video signal lost (${if (silent) "no frames" else "frozen picture"})")
                listener.onSignalChanged(false)
            }
            if (silent && now - lastRecommitAt > RECOMMIT_INTERVAL_MS) {
                // Some cards need a nudge to resume once the source comes back.
                lastRecommitAt = now
                val result = recommit(conn)
                DiagnosticLog.log("Uvc", "recommit while silent: $result")
            }

            if (got < 0) continue // timeout: the source is silent, keep waiting

            // A payload ends with a short read (a zero-length one included) or at its
            // negotiated maximum size; only its first read carries the UVC header.
            if (got > 0) {
                if (payloadLength + got > payload.size) payloadLength = 0 // lost sync: drop it
                System.arraycopy(chunk, 0, payload, payloadLength, got)
                payloadLength += got
            }
            if (got == chunk.size && payloadLength < payloadSize) continue
            val n = payloadLength
            payloadLength = 0
            if (n < 2) continue

            val headerLength = payload[0].toInt() and 0xff
            val headerInfo = payload[1].toInt() and 0xff

            if (headerInfo and 0x40 != 0) { // error bit: drop the partial frame
                assembly.reset()
                continue
            }
            if (headerLength in 2..n) {
                assembly.write(payload, headerLength, n - headerLength)
            }

            if (headerInfo and 0x02 != 0) { // end of frame
                if (assembly.size() > 4) {
                    val jpeg = assembly.toByteArray()
                    if (isJpeg(jpeg)) {
                        val frameAt = System.currentTimeMillis()
                        lastFrameAt = frameAt
                        val signature = signatureOf(jpeg)
                        if (signature != lastSignature) {
                            lastSignature = signature
                            lastChangeAt = frameAt
                            if (!hasSignal) {
                                hasSignal = true
                                DiagnosticLog.log("Uvc", "video signal restored")
                                listener.onSignalChanged(true)
                            }
                        }
                        listener.onFrame(jpeg)
                    }
                }
                assembly.reset()
            }
        }
    }

    /** Re-sends the committed format so a card that halted its stream starts again. */
    /** Returns the control transfer result: negative means the card did not answer. */
    private fun recommit(conn: UsbDeviceConnection): Int {
        val settings = committed ?: return -1
        return runCatching {
            conn.controlTransfer(
                RT_SET, SET_CUR, VS_COMMIT_CONTROL, committedInterface,
                settings, settings.size, 1000
            )
        }.getOrDefault(-1)
    }

    private fun negotiate(
        conn: UsbDeviceConnection,
        interfaceNumber: Int,
        formatIndex: Int,
        frameIndex: Int,
        frameInterval: Int
    ): ByteArray? {
        val probe = ByteArray(26)
        probe[0] = 0x01 // bmHint: hold dwFrameInterval
        probe[2] = formatIndex.toByte()
        probe[3] = frameIndex.toByte()
        for (k in 0..3) probe[4 + k] = ((frameInterval shr (8 * k)) and 0xff).toByte()

        if (conn.controlTransfer(RT_SET, SET_CUR, VS_PROBE_CONTROL, interfaceNumber, probe, probe.size, 2000) < 0) {
            return null
        }

        val negotiated = ByteArray(26)
        if (conn.controlTransfer(RT_GET, GET_CUR, VS_PROBE_CONTROL, interfaceNumber, negotiated, negotiated.size, 2000) < 26) {
            return null
        }
        if (conn.controlTransfer(RT_SET, SET_CUR, VS_COMMIT_CONTROL, interfaceNumber, negotiated, negotiated.size, 2000) < 0) {
            return null
        }
        return negotiated
    }

    private fun chooseFrame(frames: List<FrameSpec>, width: Int, height: Int): FrameSpec? {
        frames.firstOrNull { it.width == width && it.height == height }?.let { return it }
        val target = width.toLong() * height
        return frames.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - target) }
    }

    private fun chooseInterval(frame: FrameSpec, fps: Int): Int {
        val wanted = (INTERVAL_UNITS_PER_SECOND / fps.coerceAtLeast(1)).toInt()
        if (frame.intervals.isEmpty()) return frame.defaultInterval
        return frame.intervals.minByOrNull { kotlin.math.abs(it - wanted) } ?: frame.defaultInterval
    }

    private fun parseStreamingInfo(raw: ByteArray?): StreamingInfo? {
        if (raw == null) return null

        fun u8(o: Int) = raw[o].toInt() and 0xff
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun u32(o: Int) = u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)

        var interfaceNumber = -1
        var endpointAddress = 0
        var mjpegFormatIndex = -1
        val frames = mutableListOf<FrameSpec>()
        var insideStreaming = false

        var i = 0
        while (i + 1 < raw.size) {
            val length = u8(i)
            if (length < 2 || i + length > raw.size) break

            when (u8(i + 1)) {
                DESC_INTERFACE -> {
                    val cls = u8(i + 5)
                    val sub = u8(i + 6)
                    insideStreaming = cls == CLASS_VIDEO && sub == SUBCLASS_VIDEOSTREAMING
                    if (insideStreaming && interfaceNumber < 0) interfaceNumber = u8(i + 2)
                }
                DESC_CS_INTERFACE -> if (insideStreaming) {
                    when (u8(i + 2)) {
                        VS_INPUT_HEADER -> endpointAddress = u8(i + 6)
                        VS_FORMAT_MJPEG -> mjpegFormatIndex = u8(i + 3)
                        VS_FRAME_MJPEG -> {
                            val intervalCount = u8(i + 25)
                            val intervals = mutableListOf<Int>()
                            for (k in 0 until intervalCount) {
                                val off = i + 26 + k * 4
                                if (off + 4 <= i + length) intervals.add(u32(off))
                            }
                            frames.add(
                                FrameSpec(
                                    index = u8(i + 3),
                                    width = u16(i + 5),
                                    height = u16(i + 7),
                                    defaultInterval = u32(i + 21),
                                    intervals = intervals
                                )
                            )
                        }
                    }
                }
            }
            i += length
        }

        if (interfaceNumber < 0 || mjpegFormatIndex < 0 || endpointAddress == 0 || frames.isEmpty()) {
            DiagnosticLog.log("Uvc", "descriptor parse incomplete: iface=$interfaceNumber ep=$endpointAddress " +
                "fmt=$mjpegFormatIndex frames=${frames.size}")
            return null
        }
        return StreamingInfo(interfaceNumber, endpointAddress, mjpegFormatIndex, frames)
    }

    /** Cheap fingerprint: length plus ~64 bytes sampled across the whole frame. */
    private fun signatureOf(data: ByteArray): Long {
        var hash = data.size.toLong()
        val step = (data.size / 64).coerceAtLeast(1)
        var i = 0
        while (i < data.size) {
            hash = hash * 31 + data[i]
            i += step
        }
        return hash
    }

    private fun isJpeg(data: ByteArray) =
        data.size > 4 &&
            (data[0].toInt() and 0xff) == 0xFF && (data[1].toInt() and 0xff) == 0xD8 &&
            (data[data.size - 2].toInt() and 0xff) == 0xFF && (data[data.size - 1].toInt() and 0xff) == 0xD9

    private fun le32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)

    private fun fail(conn: UsbDeviceConnection, message: String): String {
        DiagnosticLog.log("Uvc", "start failed: $message")
        releaseUsb()
        return message
    }

    private fun releaseUsb() {
        claimed?.let { itf -> runCatching { connection?.releaseInterface(itf) } }
        claimed = null
        runCatching { connection?.close() }
        connection = null
    }
}
