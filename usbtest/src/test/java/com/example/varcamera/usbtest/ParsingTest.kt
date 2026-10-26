package com.example.varcamera.usbtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The descriptor parser and the frame assembler run on camera data nobody can test here,
 * so they are checked against hand-built descriptors and payloads in the shapes the UVC
 * specification allows: a USB 3 isochronous camera, a USB 2 bulk capture card, and
 * frames ended by EOF bits, by FID flips, padded, broken or interrupted.
 */
class ParsingTest {

    // ---- descriptor building ------------------------------------------------------------

    private fun d(type: Int, vararg body: Int) = intArrayOf(body.size + 2, type, *body)
    private fun le16(v: Int) = intArrayOf(v and 0xff, (v shr 8) and 0xff)
    private fun le24(v: Int) = intArrayOf(v and 0xff, (v shr 8) and 0xff, (v shr 16) and 0xff)
    private fun le32(v: Int) = intArrayOf(v and 0xff, (v shr 8) and 0xff, (v shr 16) and 0xff, (v ushr 24) and 0xff)
    private fun bytes(vararg parts: IntArray) = parts.flatMap { it.toList() }.map { it.toByte() }.toByteArray()

    private val nv12 = intArrayOf('N'.code, 'V'.code, '1'.code, '2'.code, 0, 0, 0x10, 0, 0x80, 0, 0, 0xAA, 0, 0x38, 0x9B, 0x71)

    /** A USB 3 camera in the shape Sony-like UVC 1.1 devices use: isochronous slots, MJPEG + NV12, UAC audio. */
    private fun superSpeedIsoCamera() = bytes(
        d(1, *le16(0x0320), 0xEF, 2, 1, 9, *le16(0x054c), *le16(0x0de2), *le16(0x0100), 1, 2, 3, 1),
        d(2, *le16(0), 4, 1, 0, 0x80, 112),
        d(0x0B, 0, 2, 14, 3, 0, 0),
        d(4, 0, 0, 1, 14, 1, 0, 0),
        d(0x24, 0x01, *le16(0x0110), *le16(0), *le32(48_000_000), 1, 1),
        d(0x24, 0x02, 1, *le16(0x0201), 0, 0, *le16(0), *le16(0), *le16(0), 3, 0, 0, 0),
        d(0x24, 0x03, 2, *le16(0x0101), 0, 1, 0),
        d(5, 0x83, 0x03, *le16(16), 8),
        d(0x30, 0, 0, *le16(16)),
        d(4, 1, 0, 0, 14, 2, 0, 0),
        d(0x24, 0x01, 2, *le16(0), 0x81, 0, 2, 0, 0, 0, 1, 0, 0),
        d(0x24, 0x06, 1, 2, 1, 1, 0, 0, 0, 0),
        d(0x24, 0x07, 1, 0, *le16(1920), *le16(1080), *le32(1), *le32(2), *le32(4_147_200), *le32(200_000), 3,
            *le32(166_666), *le32(200_000), *le32(333_333)),
        d(0x24, 0x07, 2, 0, *le16(1280), *le16(720), *le32(1), *le32(2), *le32(1_843_200), *le32(333_333), 0,
            *le32(166_666), *le32(400_000), *le32(1)),
        d(0x24, 0x04, 2, 1, *nv12, 12, 1, 0, 0, 0, 0),
        d(0x24, 0x05, 1, 0, *le16(1920), *le16(1080), *le32(1), *le32(2), *le32(3_110_400), *le32(333_333), 1,
            *le32(333_333)),
        d(0x24, 0x0D, 1, 1, 4),
        d(4, 1, 1, 1, 14, 2, 0, 0),
        d(5, 0x81, 0x05, *le16(1024), 1),
        d(0x30, 15, 0, *le16(16_384)),
        d(4, 1, 2, 1, 14, 2, 0, 0),
        d(5, 0x81, 0x05, *le16(1024), 1),
        d(0x30, 15, 2, *le16(49_152)),
        d(4, 2, 0, 0, 1, 1, 0, 0),
        d(0x24, 0x01, *le16(0x0100), *le16(30), 1, 3),
        d(4, 3, 0, 0, 1, 2, 0, 0),
        d(4, 3, 1, 1, 1, 2, 0, 0),
        d(0x24, 0x01, 3, 1, *le16(1)),
        d(0x24, 0x02, 1, 2, 2, 16, 1, *le24(48_000)),
        d(5, 0x84, 0x05, *le16(192), 4, 0, 0)
    )

    /** The shape of the project's HDMI capture card: USB 2, UVC 1.0, one bulk endpoint on alt 0. */
    private fun bulkCaptureCard() = bytes(
        d(1, *le16(0x0200), 0xEF, 2, 1, 64, *le16(0x345f), *le16(0x2130), *le16(0x0100), 1, 2, 3, 1),
        d(2, *le16(0), 2, 1, 0, 0x80, 250),
        d(4, 0, 0, 1, 14, 1, 0, 0),
        d(0x24, 0x01, *le16(0x0100), *le16(0), *le32(48_000_000), 1, 1),
        d(5, 0x83, 0x03, *le16(16), 8),
        d(4, 1, 0, 1, 14, 2, 0, 0),
        d(0x24, 0x01, 1, *le16(0), 0x81, 0, 2, 0, 0, 0, 1, 0),
        d(0x24, 0x06, 1, 1, 1, 1, 0, 0, 0, 0),
        d(0x24, 0x07, 1, 0, *le16(1920), *le16(1080), *le32(1), *le32(2), *le32(4_147_200), *le32(200_000), 1,
            *le32(200_000)),
        d(5, 0x81, 0x02, *le16(512), 0)
    )

    @Test
    fun parsesSuperSpeedIsochronousCamera() {
        val parsed = UsbDescriptors.parse(superSpeedIsoCamera())

        assertEquals(0x0320, parsed.bcdUsb)
        assertEquals(0x0110, parsed.bcdUvc)
        assertEquals(34, parsed.controlSize)
        assertTrue(parsed.superSpeed)

        val vs = parsed.streaming.single()
        assertEquals(1, vs.interfaceNumber)
        assertEquals(0x81, vs.endpointAddress)
        assertEquals(2, vs.formats.size)

        val mjpeg = vs.formats[0]
        assertTrue(mjpeg.isMjpeg)
        assertEquals(2, mjpeg.frames.size)
        val fullHd = mjpeg.frames[0]
        assertEquals(1920, fullHd.width)
        assertEquals(1080, fullHd.height)
        assertEquals(listOf(166_666, 200_000, 333_333), fullHd.intervals)
        assertEquals(200_000, fullHd.defaultInterval)
        assertEquals(4_147_200, fullHd.maxFrameBytes)
        val hd = mjpeg.frames[1]
        assertTrue(hd.intervals.isEmpty())
        assertEquals(166_666, hd.minInterval)
        assertEquals(400_000, hd.maxInterval)
        assertEquals(1, hd.intervalStep)

        val yuv = vs.formats[1]
        assertFalse(yuv.isMjpeg)
        assertEquals("Uncompressed NV12 12 bit", yuv.name)
        assertEquals(1, yuv.frames.size)

        val slots = parsed.alternates(1)
        assertEquals(listOf(0, 1, 2), slots.map { it.alternate })
        assertTrue(slots[0].endpoints.isEmpty())
        assertTrue(slots[1].endpoints.single().isIsochronous)
        assertEquals(16_384, slots[1].endpoints.single().bytesPerInterval)
        assertEquals(49_152, slots[2].endpoints.single().bytesPerInterval)

        assertTrue(parsed.lines.any { it.contains("Audio format: 2 channel(s), 16 bit, 48000 Hz") })
        assertTrue(parsed.lines.any { it.contains("burst=16") && it.contains("49152 bytes per interval") })
        assertFalse(parsed.lines.any { it.startsWith("Descriptor walk stopped") })
    }

    @Test
    fun parsesBulkCaptureCard() {
        val parsed = UsbDescriptors.parse(bulkCaptureCard())

        assertEquals(0x0100, parsed.bcdUvc)
        assertEquals(26, parsed.controlSize)
        assertFalse(parsed.superSpeed)
        val alt0 = parsed.alternates(1).single()
        assertTrue(alt0.endpoints.single().isBulk)
        assertEquals(1, parsed.streaming.single().formats.single().frames.single().intervals.size)
    }

    @Test
    fun highBandwidthUsb2EndpointCountsExtraTransactions() {
        // wMaxPacketSize 0x1400: 1024 bytes, 2 extra transactions per microframe.
        assertEquals(3072, UsbDescriptors.Endpoint(0x81, 0x05, 0x1400, 1).bytesPerInterval)
        assertEquals(1024, UsbDescriptors.Endpoint(0x81, 0x05, 0x0400, 1).bytesPerInterval)
    }

    @Test
    fun truncatedDescriptorsDoNotCrash() {
        val raw = superSpeedIsoCamera()
        for (cut in listOf(5, 40, 100, raw.size - 3)) {
            UsbDescriptors.parse(raw.copyOf(cut))
        }
    }

    // ---- frame assembly -------------------------------------------------------------------

    private val frames = mutableListOf<ByteArray>()
    private fun assembler(jpeg: Boolean = true) = PayloadAssembler(jpeg) { data, length -> frames += data.copyOf(length) }

    private fun jpeg(size: Int, fill: Int): ByteArray =
        ByteArray(size) { fill.toByte() }.also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
            it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte()
        }

    /** One payload: a 12-byte header (with PTS and SCR flags, as cameras send) and data. */
    private fun payload(fid: Int, data: ByteArray, eof: Boolean = false, error: Boolean = false, at: Int = 0): ByteBuffer {
        var info = 0x80 or 0x0C or fid
        if (eof) info = info or 0x02
        if (error) info = info or 0x40
        val buffer = ByteArray(at + 12 + data.size)
        buffer[at] = 12
        buffer[at + 1] = info.toByte()
        data.copyInto(buffer, at + 12)
        return ByteBuffer.wrap(buffer)
    }

    private fun PayloadAssembler.send(fid: Int, data: ByteArray, eof: Boolean = false, error: Boolean = false) {
        feed(payload(fid, data, eof, error), 0, 12 + data.size)
    }

    private fun PayloadAssembler.sendInPieces(fid: Int, frame: ByteArray, pieces: Int, eof: Boolean) {
        val step = (frame.size + pieces - 1) / pieces
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + step, frame.size)
            send(fid, frame.copyOfRange(offset, end), eof = eof && end == frame.size)
            offset = end
        }
    }

    @Test
    fun framesEndedByEofBits() {
        val a = assembler()
        val first = jpeg(5000, 0x11)
        val second = jpeg(3000, 0x22)
        a.sendInPieces(0, first, 3, eof = true)
        a.send(1, ByteArray(0)) // header-only payload between frames
        a.sendInPieces(1, second, 4, eof = true)

        assertEquals(2, a.framesOk)
        assertArrayEquals(first, frames[0])
        assertArrayEquals(second, frames[1])
        assertEquals(2, a.eofBits)
        assertEquals(1, a.headerOnlyPayloads)
    }

    @Test
    fun framesEndedByFidFlipsOnly() {
        val a = assembler()
        val frameA = jpeg(4000, 0x11)
        val frameB = jpeg(4100, 0x22)
        val frameC = jpeg(4200, 0x33)
        a.sendInPieces(0, frameA, 3, eof = false)
        a.sendInPieces(1, frameB, 3, eof = false)
        a.sendInPieces(0, frameC, 3, eof = false)

        // The third frame only ends when the next one starts.
        assertEquals(2, a.framesOk)
        assertArrayEquals(frameA, frames[0])
        assertArrayEquals(frameB, frames[1])
        assertEquals(2, a.fidToggles)
    }

    @Test
    fun paddingAfterJpegEndIsTrimmed() {
        val a = assembler()
        val picture = jpeg(2000, 0x44)
        a.send(0, picture + ByteArray(300), eof = true)

        assertEquals(1, a.framesOk)
        assertEquals(1, a.paddedFrames)
        assertArrayEquals(picture, frames[0])
    }

    @Test
    fun errorBitsAndLostPacketsDropTheFrame() {
        val a = assembler()
        val picture = jpeg(2000, 0x55)
        a.send(0, picture.copyOfRange(0, 1000))
        a.send(0, picture.copyOfRange(1000, 2000), eof = true, error = true)
        a.send(1, picture.copyOfRange(0, 1000))
        a.markLost()
        a.send(1, picture.copyOfRange(1000, 2000), eof = true)
        a.send(0, picture, eof = true)

        assertEquals(1, a.framesOk)
        assertEquals(2, a.droppedError)
    }

    @Test
    fun nonJpegDataIsRejectedUnlessRawFormat() {
        val noise = ByteArray(1500) { 0x10 }
        val jpegCheck = assembler()
        jpegCheck.send(0, noise, eof = true)
        assertEquals(0, jpegCheck.framesOk)
        assertEquals(1, jpegCheck.droppedNoStart)

        frames.clear()
        val raw = assembler(jpeg = false)
        raw.send(0, noise, eof = true)
        assertEquals(1, raw.framesOk)
        assertEquals(1500, frames.single().size)
    }

    @Test
    fun readsPayloadsAtAnOffsetInsideABiggerBuffer() {
        // Isochronous packets sit at p * packetSize inside one URB buffer.
        val a = assembler()
        val picture = jpeg(900, 0x66)
        val buffer = payload(0, picture, eof = true, at = 3 * 1024)
        a.feed(buffer, 3 * 1024, 12 + picture.size)

        assertEquals(1, a.framesOk)
        assertArrayEquals(picture, frames[0])
    }

    @Test
    fun badHeadersAreCountedAndSkipped() {
        val a = assembler()
        a.feed(ByteBuffer.wrap(byteArrayOf(40, 0x8E.toByte(), 1, 2)), 0, 4) // header longer than payload
        a.feed(ByteBuffer.wrap(byteArrayOf(1)), 0, 1)
        assertEquals(2, a.badHeaders)
        assertEquals(0, a.framesOk)
    }
}
