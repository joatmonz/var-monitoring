package com.example.varcamera.usbtest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Process
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The whole camera test. Reads the camera's USB description, negotiates a video format
 * the way Linux's uvcvideo does, then tries to receive frames over whichever transfer
 * type the camera uses: bulk (like the HDMI capture card) or isochronous (like most
 * cameras). Every step and count goes into the log. When the first attempt shows no
 * picture it falls back to other settings, so one run answers as much as possible.
 */
class UvcProbe(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val log: TestLog,
    private val ui: Ui
) {
    interface Ui {
        fun status(text: String)
        fun preview(jpeg: ByteArray)
        fun finished(summary: String)
    }

    private class Attempt(
        val label: String,
        val format: UsbDescriptors.Format,
        val frame: UsbDescriptors.Frame,
        val interval: Int,
        /** Use the biggest isochronous slot instead of the smallest one that fits. */
        val widestSlot: Boolean,
        val seconds: Int
    )

    private class Outcome(val label: String, val frames: Long, val fps: Double, val transfer: String, val problem: String?)

    companion object {
        private const val RT_SET = 0x21
        private const val RT_GET = 0xA1
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val GET_DEF = 0x87
        private const val VS_PROBE = 0x0100
        private const val VS_COMMIT = 0x0200

        private const val TARGET_WIDTH = 1920
        private const val TARGET_HEIGHT = 1080
        private const val TARGET_FPS = 50

        /** Enough good frames to call it a working picture. */
        private const val GOOD_FRAMES = 10
        private const val ENOMEM = 12

        private val ERRNO_NAMES = mapOf(
            2 to "ENOENT", 5 to "EIO", 12 to "ENOMEM", 16 to "EBUSY", 18 to "EXDEV (missed)",
            19 to "ENODEV", 22 to "EINVAL", 28 to "ENOSPC", 32 to "EPIPE (stall)", 63 to "ENOSR",
            71 to "EPROTO", 75 to "EOVERFLOW", 84 to "EILSEQ", 104 to "ECONNRESET",
            108 to "ESHUTDOWN", 110 to "ETIMEDOUT", 121 to "EREMOTEIO"
        )

        private fun errno(code: Int) = "$code ${ERRNO_NAMES[-code] ?: ERRNO_NAMES[code] ?: ""}".trim()
        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
        private fun putLe32(b: ByteArray, o: Int, v: Int) {
            for (k in 0..3) b[o + k] = (v shr (8 * k)).toByte()
        }
    }

    @Volatile private var stopped = false
    @Volatile private var activePump: IsoPump? = null

    fun stop() {
        stopped = true
        activePump?.stopRequested = true
    }

    fun run() {
        try {
            test()
        } catch (t: Throwable) {
            log.log("TEST CRASHED: ${Log.getStackTraceString(t)}")
            ui.finished("The test crashed.")
        }
    }

    private fun test() {
        log.section("Testing %04x:%04x %s".format(device.vendorId, device.productId, device.productName ?: ""))
        ui.status("Reading the camera's USB description...")
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            log.log("openDevice failed")
            ui.finished("NO PICTURE: could not open the camera.")
            return
        }
        try {
            val raw = conn.rawDescriptors
            if (raw == null) {
                log.log("No raw descriptors")
                ui.finished("NO PICTURE: could not read the camera's USB description.")
                return
            }
            log.log("Raw descriptors, ${raw.size} bytes (in the log file)")
            log.hex(raw)
            val d = UsbDescriptors.parse(raw)
            log.section("Parsed descriptors")
            d.lines.forEach { log.log(it) }
            log.log(if (d.superSpeed) "Link: USB 3 (SuperSpeed)" else "Link: USB 2 or slower (no SuperSpeed descriptors)")

            val vs = d.streaming.firstOrNull { s -> s.formats.any { it.frames.isNotEmpty() } }
            if (vs == null) {
                log.log("No video streaming interface with formats")
                ui.finished("NO PICTURE: the camera offers no video formats.")
                return
            }
            val alt0 = androidInterface(vs.interfaceNumber, 0)
            if (alt0 == null) {
                log.log("Android does not list interface ${vs.interfaceNumber} alt 0")
                ui.finished("NO PICTURE: video interface missing.")
                return
            }
            if (!claim(conn, alt0)) {
                ui.finished("Another app is using the camera. Close the VAR app (swipe it away), then tap START TEST.")
                return
            }
            try {
                val outcomes = mutableListOf<Outcome>()
                for ((n, attempt) in plan(d, vs).withIndex()) {
                    if (stopped || outcomes.any { it.frames >= GOOD_FRAMES }) break
                    outcomes += runAttempt(n + 1, conn, d, vs, alt0, attempt)
                }
                summarize(outcomes)
            } finally {
                runCatching { conn.setInterface(alt0) }
                conn.releaseInterface(alt0)
            }
        } finally {
            conn.close()
        }
    }

    /** First the setting the VAR app wants (MJPEG 1080p at 50 fps); then fallbacks. */
    private fun plan(d: UsbDescriptors, vs: UsbDescriptors.Streaming): List<Attempt> {
        val attempts = mutableListOf<Attempt>()
        val isochronous = d.alternates(vs.interfaceNumber)
            .none { it.alternate == 0 && it.endpoints.any { ep -> ep.isIn && ep.isBulk } }

        vs.formats.firstOrNull { it.isMjpeg && it.frames.isNotEmpty() }?.let { mjpeg ->
            val main = chooseFrame(mjpeg)
            val mainInterval = chooseInterval(main, TARGET_FPS)
            attempts += Attempt(label(mjpeg, main, mainInterval), mjpeg, main, mainInterval, false, 8)
            if (isochronous) {
                attempts += Attempt(label(mjpeg, main, mainInterval) + ", widest USB slot", mjpeg, main, mainInterval, true, 6)
            }
            val small = mjpeg.frames.minByOrNull { it.width * it.height }!!
            if (small !== main) {
                val interval = chooseInterval(small, 30)
                attempts += Attempt(label(mjpeg, small, interval), mjpeg, small, interval, false, 6)
            }
        }
        // A non-MJPEG stream tells whether the transport works at all, picture aside.
        vs.formats.firstOrNull { !it.isMjpeg && it.frames.isNotEmpty() }?.let { other ->
            val small = other.frames.minByOrNull { it.width * it.height }!!
            val interval = chooseInterval(small, 30)
            attempts += Attempt(label(other, small, interval), other, small, interval, false, 6)
        }
        return attempts
    }

    private fun label(format: UsbDescriptors.Format, frame: UsbDescriptors.Frame, interval: Int) =
        "${format.name} ${frame.width}x${frame.height} @ ${UsbDescriptors.fps(interval)} fps"

    private fun chooseFrame(format: UsbDescriptors.Format): UsbDescriptors.Frame {
        format.frames.firstOrNull { it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT }?.let { return it }
        val target = TARGET_WIDTH.toLong() * TARGET_HEIGHT
        return format.frames.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - target) }!!
    }

    private fun chooseInterval(frame: UsbDescriptors.Frame, fps: Int): Int {
        val wanted = 10_000_000 / fps
        if (frame.intervals.isNotEmpty()) return frame.intervals.minByOrNull { kotlin.math.abs(it - wanted) }!!
        if (frame.minInterval > 0 && frame.maxInterval >= frame.minInterval) {
            val clamped = wanted.coerceIn(frame.minInterval, frame.maxInterval)
            val step = frame.intervalStep.coerceAtLeast(1)
            return frame.minInterval + (clamped - frame.minInterval) / step * step
        }
        return frame.defaultInterval
    }

    private fun runAttempt(
        n: Int,
        conn: UsbDeviceConnection,
        d: UsbDescriptors,
        vs: UsbDescriptors.Streaming,
        alt0: UsbInterface,
        a: Attempt
    ): Outcome {
        log.section("Attempt $n: ${a.label}")
        ui.status("Attempt $n: ${a.label}...")
        val control = negotiate(conn, d, vs.interfaceNumber, a)
        if (control == null) {
            log.log("RESULT attempt $n: format negotiation failed")
            return Outcome(a.label, 0, 0.0, "-", "format negotiation failed")
        }
        val maxFrame = le32(control, 18)
        val maxPayload = le32(control, 22)

        val collector = FrameCollector(a.format.isMjpeg)
        val assembler = PayloadAssembler(a.format.isMjpeg) { data, length -> collector.onFrame(data, length) }
        val bulkEndpoint = (0 until alt0.endpointCount).map { alt0.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }

        val started = System.nanoTime()
        val transfer: String
        val problem: String?
        if (bulkEndpoint != null) {
            transfer = "bulk"
            problem = readBulk(conn, bulkEndpoint, maxPayload, a.seconds, assembler)
        } else {
            transfer = "isochronous"
            problem = readIsochronous(conn, d, vs, a, maxPayload, assembler)
            log.log("SET_INTERFACE alt 0 (stop streaming) -> ${conn.setInterface(alt0)}")
        }
        report(assembler, collector, (System.nanoTime() - started) / 1e9, maxFrame)

        val why = problem ?: if (assembler.framesOk == 0L) "data arrived but no complete frames" else null
        log.log("RESULT attempt $n: ${assembler.framesOk} good frames, %.1f fps via %s%s".format(
            collector.fps, transfer, why?.let { " ($it)" } ?: ""
        ))
        return Outcome(a.label, assembler.framesOk, collector.fps, transfer, why)
    }

    /** Probe and commit, as Linux does it; logs every answer from the camera. */
    private fun negotiate(conn: UsbDeviceConnection, d: UsbDescriptors, itf: Int, a: Attempt): ByteArray? {
        val size = d.controlSize
        var base: ByteArray? = null
        for ((request, name) in listOf(GET_DEF to "GET_DEF", GET_CUR to "GET_CUR")) {
            val answer = ByteArray(size)
            val r = conn.controlTransfer(RT_GET, request, VS_PROBE, itf, answer, size, 1000)
            log.log("$name(PROBE) -> $r" + if (r >= 26) "  " + describeControl(answer, r) else "")
            if (base == null && r >= 26) base = answer
        }

        val probe = (base ?: ByteArray(size)).copyOf(size)
        probe[0] = 0x01 // bmHint: keep the frame interval
        probe[1] = 0
        probe[2] = a.format.index.toByte()
        probe[3] = a.frame.index.toByte()
        putLe32(probe, 4, a.interval)

        var used = size
        var r = conn.controlTransfer(RT_SET, SET_CUR, VS_PROBE, itf, probe, size, 2000)
        log.log("SET_CUR(PROBE, $size bytes) format #${a.format.index} frame #${a.frame.index} interval ${a.interval} -> $r")
        if (r < 0) {
            for (other in listOf(26, 34, 48).filter { it != size }) {
                r = conn.controlTransfer(RT_SET, SET_CUR, VS_PROBE, itf, probe.copyOf(other), other, 2000)
                log.log("SET_CUR(PROBE, $other bytes) -> $r")
                if (r >= 0) {
                    used = other
                    break
                }
            }
            if (r < 0) return null
        }

        val result = ByteArray(used)
        r = conn.controlTransfer(RT_GET, GET_CUR, VS_PROBE, itf, result, used, 2000)
        log.log("GET_CUR(PROBE) -> $r" + if (r >= 26) "  " + describeControl(result, r) else "")
        if (r < 26) return null
        if (result[2] != probe[2] || result[3] != probe[3]) {
            log.log("NOTE: the camera answered with format #${result[2]} frame #${result[3]} instead")
        }
        r = conn.controlTransfer(RT_SET, SET_CUR, VS_COMMIT, itf, result, used, 2000)
        log.log("SET_CUR(COMMIT) -> $r")
        return if (r >= 0) result else null
    }

    private fun describeControl(b: ByteArray, length: Int): String {
        val interval = le32(b, 4)
        val text = StringBuilder(
            "hint=0x%04x format=%d frame=%d interval=%d (%s fps) keyRate=%d pRate=%d quality=%d window=%d delay=%d maxFrame=%d maxPayload=%d".format(
                le16(b, 0), b[2].toInt() and 0xff, b[3].toInt() and 0xff, interval, UsbDescriptors.fps(interval),
                le16(b, 8), le16(b, 10), le16(b, 12), le16(b, 14), le16(b, 16), le32(b, 18), le32(b, 22)
            )
        )
        if (length >= 34) {
            text.append(" clock=%d framing=0x%02x versions=%d/%d/%d".format(
                le32(b, 26).toLong() and 0xffffffffL, b[30].toInt() and 0xff,
                b[31].toInt() and 0xff, b[32].toInt() and 0xff, b[33].toInt() and 0xff
            ))
        }
        return text.toString()
    }

    private fun readBulk(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        maxPayload: Int,
        seconds: Int,
        assembler: PayloadAssembler
    ): String? {
        // A UVC payload can be far bigger than one read can be (the Sony A7 IV sends a whole
        // 1.3 MB frame as one payload, and the phone refuses reads that big). So read in
        // chunks and rebuild each payload, as Linux does: a payload ends with a short read
        // or when it reaches the negotiated maximum size.
        log.log("Bulk endpoint 0x%02x, max packet %d, payloads up to %d bytes".format(
            endpoint.address, endpoint.maxPacketSize, maxPayload
        ))
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val start = System.nanoTime()
        val deadline = start + seconds * 1_000_000_000L
        var anyData = false

        for (chunkSize in listOf(16384, 65536, 4096, 512)) {
            if (stopped || System.nanoTime() >= deadline) break
            log.log("Reading in $chunkSize-byte chunks")
            val chunk = ByteArray(chunkSize)
            val payload = ByteArray(maxOf(maxPayload, chunkSize) + chunkSize)
            val payloadBuffer = ByteBuffer.wrap(payload)
            var payloadLength = 0
            var reads = 0L
            var failures = 0L
            var quickFailures = 0L
            var payloads = 0L
            var oversized = 0L
            var firstDataAt = 0L
            val sizes = mutableListOf<Int>()
            val sizeStart = System.nanoTime()

            fun endPayload() {
                if (payloadLength == 0) return
                payloads++
                if (sizes.size < 10) sizes += payloadLength
                assembler.feed(payloadBuffer, 0, payloadLength)
                payloadLength = 0
            }

            while (!stopped && System.nanoTime() < deadline) {
                // No data at all after 1.5 s: this chunk size does not work, try the next.
                if (reads == 0L && System.nanoTime() - sizeStart > 1_500_000_000L) break
                val t = System.nanoTime()
                val got = conn.bulkTransfer(endpoint, chunk, chunkSize, 500)
                if (got < 0) {
                    failures++
                    if (System.nanoTime() - t < 50_000_000L) quickFailures++
                    continue
                }
                reads++
                if (got == 0) {
                    endPayload() // zero-length packet: the payload ended on a packet boundary
                    continue
                }
                if (firstDataAt == 0L) firstDataAt = System.nanoTime()
                if (payloadLength + got > payload.size) {
                    oversized++
                    endPayload()
                }
                System.arraycopy(chunk, 0, payload, payloadLength, got)
                payloadLength += got
                if (got < chunkSize || payloadLength >= maxPayload) endPayload()
            }
            endPayload()

            log.log("Bulk ($chunkSize-byte chunks): $reads reads, $failures failed ($quickFailures failed at once), " +
                "$payloads payloads, $oversized oversized, first data after " +
                (if (firstDataAt == 0L) "never" else "${(firstDataAt - sizeStart) / 1_000_000} ms") +
                ", first payload sizes: ${sizes.joinToString("/").ifEmpty { "none" }}")
            if (reads > 0) {
                anyData = true
                break
            }
        }
        return if (anyData) null else "no data on the bulk endpoint"
    }

    private fun readIsochronous(
        conn: UsbDeviceConnection,
        d: UsbDescriptors,
        vs: UsbDescriptors.Streaming,
        a: Attempt,
        maxPayload: Int,
        assembler: PayloadAssembler
    ): String? {
        // Alternate settings ("slots") that carry the video endpoint, smallest first.
        val slots = d.alternates(vs.interfaceNumber).mapNotNull { itf ->
            itf.endpoints.firstOrNull {
                it.isIn && it.isIsochronous && (vs.endpointAddress == 0 || it.address == vs.endpointAddress)
            }?.takeIf { it.bytesPerInterval > 0 }?.let { itf to it }
        }.sortedBy { it.second.bytesPerInterval }
        if (slots.isEmpty()) {
            log.log("No isochronous video endpoint")
            return "no isochronous endpoint"
        }
        log.log("Camera wants $maxPayload bytes per interval; slots: " +
            slots.joinToString { "alt ${it.first.alternate}=${it.second.bytesPerInterval}" })

        val fitting = slots.indexOfFirst { it.second.bytesPerInterval >= maxPayload }
        val first = if (a.widestSlot || fitting < 0) slots.lastIndex else fitting
        for (k in first until minOf(slots.size, first + 3)) {
            val (slot, endpoint) = slots[k]
            val itf = androidInterface(vs.interfaceNumber, slot.alternate) ?: continue
            val ok = conn.setInterface(itf)
            log.log("SET_INTERFACE alt ${slot.alternate} (${endpoint.bytesPerInterval} bytes per interval) -> $ok")
            if (ok) return pump(conn, endpoint, a.seconds, assembler)
        }
        return "the phone refused the isochronous slots"
    }

    private fun pump(
        conn: UsbDeviceConnection,
        endpoint: UsbDescriptors.Endpoint,
        seconds: Int,
        assembler: PayloadAssembler
    ): String? {
        val packetSize = endpoint.bytesPerInterval
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        // Memory budget for all transfers in flight, then the URB count; smaller on retry.
        for ((budget, urbCount) in listOf((10 shl 20) to 8, (4 shl 20) to 8, (1 shl 20) to 4)) {
            val packets = (budget / (urbCount * packetSize)).coerceIn(1, 32)
            val pump = IsoPump(packetSize, { log.log("native: $it") }, { assembler.markLost() }) { buffer, offset, length ->
                assembler.feed(buffer, offset, length)
            }
            activePump = pump
            val start = System.nanoTime()
            val rc = pump.run(conn.fileDescriptor, endpoint.address, packets, urbCount, seconds * 1000L)
            activePump = null

            log.log("Isochronous: result ${errno(rc)}, ${pump.urbs} URBs, ${pump.packets} packets " +
                "(${pump.dataPackets} with data, ${pump.emptyPackets} empty), first data after " +
                if (pump.firstDataAtNanos == 0L) "never" else "${(pump.firstDataAtNanos - start) / 1_000_000} ms")
            if (pump.packetErrors.isNotEmpty()) {
                log.log("Packet errors: " + pump.packetErrors.entries.joinToString { "${errno(it.key)} x${it.value}" })
            }
            if (pump.urbErrors.isNotEmpty()) {
                log.log("URB errors: " + pump.urbErrors.entries.joinToString { "${errno(it.key)} x${it.value}" })
            }
            pump.callbackError?.let { log.log("Callback error: ${Log.getStackTraceString(it)}") }

            if (rc == -ENOMEM && pump.urbs == 0L) {
                log.log("Not enough USB memory; retrying with smaller transfers")
                continue
            }
            return when {
                rc < 0 && pump.urbs == 0L -> "isochronous transfers refused (${errno(rc)})"
                pump.dataPackets == 0L -> "no data in the isochronous packets"
                else -> null
            }
        }
        return "isochronous transfers refused (out of memory)"
    }

    private fun report(a: PayloadAssembler, c: FrameCollector, seconds: Double, maxFrame: Int) {
        log.log("Payloads: ${a.payloads} (header only ${a.headerOnlyPayloads}, bad header ${a.badHeaders}, " +
            "error bit ${a.errorBits}), EOF bits ${a.eofBits}, FID flips ${a.fidToggles}, PTS ${a.ptsPayloads}, SCR ${a.scrPayloads}")
        log.log("Image data: %.1f MB in %.1f s = %.2f MB/s".format(a.dataBytes / 1e6, seconds, a.dataBytes / 1e6 / seconds))
        log.log("Frames: ${a.framesOk} good; dropped ${a.droppedError} with errors, ${a.droppedNoStart} without JPEG start, " +
            "${a.droppedNoEnd} without JPEG end, ${a.droppedOverflow} too big; ${a.paddedFrames} padded after the JPEG end")
        if (a.framesOk > 0) {
            val average = a.okBytes / a.framesOk
            log.log("Frame size: min %d KB, avg %d KB, max %d KB (camera said up to %d KB); first frame after %d ms; %.2f fps".format(
                a.minFrame / 1024, average / 1024, a.maxFrame / 1024, maxFrame / 1024, c.firstFrameMs, c.fps
            ))
            log.log("A 30-second clip at this rate needs about %d MB".format((average * c.fps * 30 / 1e6).toLong()))
        }
        log.log("First payload headers: " + a.headerSamples.joinToString(" | ").ifEmpty { "none" })
        c.sample?.let { if (c.jpeg) inspectJpeg(it) }
    }

    /** Structure, decode speed and a small copy of one frame, so the log shows the picture too. */
    private fun inspectJpeg(jpeg: ByteArray) {
        log.log("Sample frame: ${jpeg.size} bytes, ${jpegStructure(jpeg)}")
        val times = mutableListOf<Long>()
        var bitmap: Bitmap? = null
        repeat(5) {
            bitmap?.recycle()
            val t = System.nanoTime()
            bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            times += (System.nanoTime() - t) / 1_000_000
        }
        val decoded = bitmap
        if (decoded == null) {
            log.log("DECODE FAILED: Android could not decode the sample frame")
            return
        }
        log.log("Decoded ${decoded.width}x${decoded.height} in ${times.joinToString("/")} ms")

        val small = Bitmap.createScaledBitmap(decoded, 480, (480L * decoded.height / decoded.width).toInt().coerceAtLeast(1), true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        small.recycle()
        decoded.recycle()
        log.log("Sample picture, 480 px JPEG as base64 (${out.size()} bytes, in the log file)")
        log.log("-----BEGIN SAMPLE JPEG-----", screen = false)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).chunked(120).forEach { log.log(it, screen = false) }
        log.log("-----END SAMPLE JPEG-----", screen = false)
    }

    private fun jpegStructure(j: ByteArray): String {
        fun u8(o: Int) = if (o < j.size) j[o].toInt() and 0xff else 0
        val markers = mutableListOf<String>()
        var picture = ""
        var i = 2
        while (i + 3 < j.size) {
            if (u8(i) != 0xFF) {
                markers += "junk@$i"
                break
            }
            val m = u8(i + 1)
            if (m == 0xFF) {
                i++
                continue
            }
            markers += when (m) {
                in 0xE0..0xEF -> "APP${m - 0xE0}"
                0xDB -> "DQT"
                0xC4 -> "DHT"
                0xDD -> "DRI"
                0xDA -> "SOS"
                0xFE -> "COM"
                in 0xC0..0xCF -> "SOF${m - 0xC0}"
                else -> "%02X".format(m)
            }
            if (m in 0xC0..0xC3) {
                val components = u8(i + 9)
                val sampling = (0 until components).joinToString(",") { k ->
                    val s = u8(i + 11 + 3 * k)
                    "${s shr 4}x${s and 15}"
                }
                picture = "%dx%d, %d components, sampling %s".format(
                    (u8(i + 7) shl 8) or u8(i + 8), (u8(i + 5) shl 8) or u8(i + 6), components, sampling
                )
            }
            if (m == 0xDA) break
            i += 2 + ((u8(i + 2) shl 8) or u8(i + 3))
        }
        val huffman = if ("DHT" in markers) "own Huffman tables" else "NO Huffman tables"
        return "markers ${markers.joinToString(" ")}; $picture; $huffman"
    }

    private fun summarize(outcomes: List<Outcome>) {
        log.section("Summary")
        outcomes.forEach {
            log.log("%s: %d good frames, %.1f fps, %s%s".format(
                it.label, it.frames, it.fps, it.transfer, it.problem?.let { p -> " - $p" } ?: ""
            ))
        }
        val best = outcomes.maxByOrNull { it.frames }
        val text = if (best != null && best.frames >= GOOD_FRAMES) {
            "PICTURE OK: %s, %.1f fps (%s)".format(best.label, best.fps, best.transfer)
        } else {
            "NO PICTURE: ${best?.problem ?: "nothing could be tested"}"
        }
        log.log(text)
        ui.finished(text)
    }

    private fun claim(conn: UsbDeviceConnection, itf: UsbInterface): Boolean {
        repeat(3) { attempt ->
            if (conn.claimInterface(itf, true)) {
                log.log("Claimed interface ${itf.id}")
                return true
            }
            log.log("Could not claim interface ${itf.id} (try ${attempt + 1} of 3); is another app using it?")
            Thread.sleep(1000)
        }
        return false
    }

    private fun androidInterface(number: Int, alternate: Int): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == number && it.alternateSetting == alternate }

    private inner class FrameCollector(val jpeg: Boolean) {
        private val startedAt = System.nanoTime()
        private var firstAt = 0L
        private var lastAt = 0L
        private var lastPreviewAt = 0L
        var count = 0L; private set
        var sample: ByteArray? = null; private set

        val firstFrameMs: Long get() = if (firstAt == 0L) -1 else (firstAt - startedAt) / 1_000_000
        val fps: Double get() = if (count > 1 && lastAt > firstAt) (count - 1) * 1e9 / (lastAt - firstAt) else 0.0

        fun onFrame(data: ByteArray, length: Int) {
            val now = System.nanoTime()
            if (count == 0L) firstAt = now
            lastAt = now
            count++
            // Keep the first frame, then a later one: streams often start with a stale picture.
            if (sample == null || count == 25L) sample = data.copyOf(length)
            if (jpeg && now - lastPreviewAt > 300_000_000L) {
                lastPreviewAt = now
                ui.preview(data.copyOf(length))
            }
        }
    }
}
