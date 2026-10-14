package com.example.varcamera.review

import com.example.varcamera.recording.AudioRingBuffer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Read access to a clip's sound, as saved by [AudioRingBuffer]: one continuous run of
 * 16-bit PCM whose first sample was captured at [startNanos], on the same clock as
 * the clip's frames ([MjpegClip.captureNanos]).
 */
class ClipAudio private constructor(
    private val channel: FileChannel,
    val sampleRate: Int,
    val channelCount: Int,
    private val startNanos: Long
) {
    companion object {
        fun open(dir: File): ClipAudio? {
            val file = File(dir, AudioRingBuffer.FILE_NAME)
            if (!file.isFile || file.length() <= AudioRingBuffer.HEADER_BYTES) return null
            val raf = RandomAccessFile(file, "r")
            return runCatching {
                check(raf.readInt() == AudioRingBuffer.MAGIC)
                val rate = raf.readInt()
                val channels = raf.readInt()
                val start = raf.readLong()
                ClipAudio(raf.channel, rate, channels, start)
            }.getOrElse {
                raf.close()
                null
            }
        }
    }

    val bytesPerFrame: Int get() = 2 * channelCount

    private val frameCount: Long =
        (channel.size() - AudioRingBuffer.HEADER_BYTES) / bytesPerFrame

    /** Sample frame captured at [nanos]; may fall outside the recording. */
    fun frameAt(nanos: Long): Long = (nanos - startNanos) * sampleRate / 1_000_000_000L

    /**
     * Fills [buffer] with sound from [frame] on. Outside the recording, or if the
     * clip was closed under the reader, it is silence.
     */
    fun read(frame: Long, buffer: ByteArray) {
        buffer.fill(0)
        val frames = buffer.size / bytesPerFrame
        val first = frame.coerceAtLeast(0L)
        val last = (frame + frames).coerceAtMost(frameCount)
        if (first >= last) return

        val target = ByteBuffer.wrap(buffer, ((first - frame) * bytesPerFrame).toInt(), ((last - first) * bytesPerFrame).toInt())
        var position = AudioRingBuffer.HEADER_BYTES + first * bytesPerFrame
        runCatching {
            while (target.hasRemaining()) {
                val read = channel.read(target, position)
                if (read < 0) break
                position += read
            }
        }
    }

    fun close() {
        runCatching { channel.close() }
    }
}
