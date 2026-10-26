package com.example.varcamera.usbtest

import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Rebuilds frames from UVC payloads (a 2..12 byte header, then image data) the way
 * Linux's uvcvideo does: a frame ends at the EOF bit, or when the FID bit flips.
 * Counts everything it sees, so the log can tell how the camera frames its stream.
 *
 * [onFrame] gets a reused buffer; copy what you keep.
 */
class PayloadAssembler(
    private val expectJpeg: Boolean,
    private val onFrame: (data: ByteArray, length: Int) -> Unit
) {
    companion object {
        private const val MAX_FRAME_BYTES = 32 * 1024 * 1024
        /** Some cameras pad frames after the JPEG end marker; look this far back for it. */
        private const val EOI_SEARCH_BYTES = 8192
    }

    private var frame = ByteArray(1024 * 1024)
    private var length = 0
    private var lastFid = -1
    private var frameError = false
    private var overflow = false

    var payloads = 0L; private set
    var headerOnlyPayloads = 0L; private set
    var badHeaders = 0L; private set
    var errorBits = 0L; private set
    var eofBits = 0L; private set
    var fidToggles = 0L; private set
    var ptsPayloads = 0L; private set
    var scrPayloads = 0L; private set
    var dataBytes = 0L; private set

    var framesOk = 0L; private set
    var okBytes = 0L; private set
    var minFrame = Int.MAX_VALUE; private set
    var maxFrame = 0; private set
    var droppedError = 0L; private set
    var droppedNoStart = 0L; private set
    var droppedNoEnd = 0L; private set
    var droppedOverflow = 0L; private set
    var paddedFrames = 0L; private set

    val headerSamples = ArrayList<String>()

    /** A packet of the current frame never arrived: the frame cannot be trusted. */
    fun markLost() {
        frameError = true
    }

    fun feed(buf: ByteBuffer, offset: Int, n: Int) {
        payloads++
        if (n < 2) {
            badHeaders++
            return
        }
        val headerLength = buf.get(offset).toInt() and 0xff
        val info = buf.get(offset + 1).toInt() and 0xff
        if (headerSamples.size < 12) {
            headerSamples += "bytes=$n header=$headerLength info=0x%02x".format(info)
        }
        if (headerLength < 2 || headerLength > n) {
            badHeaders++
            return
        }

        val fid = info and 0x01
        if (lastFid >= 0 && fid != lastFid) {
            fidToggles++
            if (length > 0 || frameError) finish()
        }
        lastFid = fid
        if (info and 0x04 != 0) ptsPayloads++
        if (info and 0x08 != 0) scrPayloads++
        if (info and 0x40 != 0) {
            errorBits++
            frameError = true
        }

        val dataLength = n - headerLength
        if (dataLength == 0) {
            headerOnlyPayloads++
        } else {
            dataBytes += dataLength
            if (!overflow && length + dataLength > frame.size) {
                if (length + dataLength > MAX_FRAME_BYTES) {
                    overflow = true
                } else {
                    frame = frame.copyOf(maxOf(frame.size * 2, length + dataLength))
                }
            }
            if (!overflow) {
                // Cast: ByteBuffer.position(int) only exists on newer Android versions.
                (buf as Buffer).position(offset + headerLength)
                buf.get(frame, length, dataLength)
                length += dataLength
            }
        }

        if (info and 0x02 != 0) {
            eofBits++
            finish()
        }
    }

    private fun finish() {
        val n = length
        val hadError = frameError
        val hadOverflow = overflow
        length = 0
        frameError = false
        overflow = false
        when {
            n == 0 -> Unit
            hadOverflow -> droppedOverflow++
            hadError -> droppedError++
            !expectJpeg -> accept(n)
            else -> {
                val end = jpegEnd(n)
                if (end > 0) {
                    if (end < n) paddedFrames++
                    accept(end)
                }
            }
        }
    }

    /** Length of the JPEG in the frame buffer up to its end marker, or -1 if it is not one. */
    private fun jpegEnd(n: Int): Int {
        if (n < 4 || frame[0] != 0xFF.toByte() || frame[1] != 0xD8.toByte()) {
            droppedNoStart++
            return -1
        }
        var i = n - 2
        val stop = maxOf(2, n - EOI_SEARCH_BYTES)
        while (i >= stop) {
            if (frame[i] == 0xFF.toByte() && frame[i + 1] == 0xD9.toByte()) return i + 2
            i--
        }
        droppedNoEnd++
        return -1
    }

    private fun accept(n: Int) {
        framesOk++
        okBytes += n
        if (n < minFrame) minFrame = n
        if (n > maxFrame) maxFrame = n
        onFrame(frame, n)
    }
}
