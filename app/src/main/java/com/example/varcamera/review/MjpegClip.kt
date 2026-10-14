package com.example.varcamera.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.varcamera.recording.FrameRingBuffer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * Read access to a saved clip: the chunk files written by [FrameRingBuffer].
 *
 * Opening scans only the per-frame headers to build an index, so any frame can be
 * read directly. Reads use positional channel I/O and are safe from several
 * threads at once.
 */
class MjpegClip private constructor(
    val dir: File,
    private val channels: List<FileChannel>,
    private val fileIndex: IntArray,
    private val offsets: LongArray,
    private val lengths: IntArray,
    private val times: LongArray,
    private val stamps: LongArray
) {
    companion object {
        /** A gap longer than this (e.g. the signal dropped) is squeezed out of playback. */
        private const val MAX_FRAME_GAP_NANOS = 100_000_000L

        private val STANDARD_RATES = listOf(24, 25, 30, 50, 60)

        fun open(dir: File): MjpegClip? {
            val files = dir.listFiles { f -> f.name.endsWith(FrameRingBuffer.CHUNK_EXTENSION) }
                ?.sortedBy { it.name }
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            val fileIndex = ArrayList<Int>()
            val offsets = ArrayList<Long>()
            val lengths = ArrayList<Int>()
            val stamps = ArrayList<Long>()
            val header = ByteArray(12)

            files.forEachIndexed { index, file ->
                RandomAccessFile(file, "r").use { raf ->
                    val size = raf.length()
                    var position = 0L
                    while (position + 12 <= size) {
                        raf.seek(position)
                        raf.readFully(header)
                        val buffer = ByteBuffer.wrap(header)
                        val stamp = buffer.long
                        val length = buffer.int
                        if (length <= 0 || position + 12 + length > size) break // truncated tail
                        fileIndex.add(index)
                        offsets.add(position + 12)
                        lengths.add(length)
                        stamps.add(stamp)
                        position += 12 + length
                    }
                }
            }
            if (stamps.isEmpty()) return null

            // Summed in nanoseconds: rounding each ~20 ms gap to whole ms would lose half
            // a ms per frame and run playback 2.5% fast.
            val times = LongArray(stamps.size)
            var elapsedNanos = 0L
            for (i in 1 until stamps.size) {
                elapsedNanos += (stamps[i] - stamps[i - 1]).coerceIn(0L, MAX_FRAME_GAP_NANOS)
                times[i] = elapsedNanos / 1_000_000L
            }

            val channels = files.map { RandomAccessFile(it, "r").channel }
            return MjpegClip(
                dir, channels,
                fileIndex.toIntArray(), offsets.toLongArray(), lengths.toIntArray(), times,
                stamps.toLongArray()
            )
        }
    }

    val frameCount: Int get() = times.size
    val durationMs: Long get() = times.last()

    /** Frame size read from the first JPEG's header, without decoding the image. */
    private val bounds: Pair<Int, Int> by lazy {
        runCatching {
            val jpeg = readFrame(0)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            options.outWidth to options.outHeight
        }.getOrDefault(0 to 0)
    }
    val frameWidth: Int get() = bounds.first
    val frameHeight: Int get() = bounds.second

    /**
     * Measured from the capture timestamps, so it reflects what was actually recorded.
     * A dropped frame or a squeezed signal gap skews the measurement slightly, so a
     * value within a couple of frames of a broadcast rate is reported as that rate.
     */
    val averageFps: Int
        get() {
            if (durationMs <= 0) return 0
            val measured = (frameCount - 1) * 1000.0 / durationMs
            val nominal = STANDARD_RATES.minByOrNull { kotlin.math.abs(it - measured) } ?: return measured.roundToInt()
            return if (kotlin.math.abs(nominal - measured) <= 2.0) nominal else measured.roundToInt()
        }

    fun timeMs(index: Int): Long = times[index.coerceIn(0, times.size - 1)]

    val firstCaptureNanos: Long get() = stamps.first()
    val lastCaptureNanos: Long get() = stamps.last()

    /** When the moment at clip time [ms] was captured, on System.nanoTime(); the sound's clock. */
    fun captureNanos(ms: Long): Long {
        val index = indexAt(ms)
        return stamps[index] + (ms - times[index]) * 1_000_000L
    }

    /** Last frame shown at or before [ms]. */
    fun indexAt(ms: Long): Int {
        var low = 0
        var high = times.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (times[mid] <= ms) low = mid else high = mid - 1
        }
        return low
    }

    /**
     * "0:03.44" = minute 0, second 3, the 45th frame of that second (numbered from 00).
     * Counted by frame index, not derived from time, so every frame step changes it
     * by exactly one even though capture timestamps jitter.
     */
    fun timecode(index: Int): String {
        val i = index.coerceIn(0, times.size - 1)
        val second = times[i] / 1000
        val firstOfSecond = if (second == 0L) 0 else indexAt(second * 1000 - 1) + 1
        val frame = (i - firstOfSecond).coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%d:%02d.%02d", second / 60, second % 60, frame)
    }

    fun readFrame(index: Int): ByteArray {
        val bytes = ByteArray(lengths[index])
        val buffer = ByteBuffer.wrap(bytes)
        val channel = channels[fileIndex[index]]
        var position = offsets[index]
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read < 0) break
            position += read
        }
        return bytes
    }

    fun thumbnail(index: Int, sampleSize: Int = 8): Bitmap? = runCatching {
        val jpeg = readFrame(index)
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }.getOrNull()

    fun close() {
        channels.forEach { runCatching { it.close() } }
    }
}
