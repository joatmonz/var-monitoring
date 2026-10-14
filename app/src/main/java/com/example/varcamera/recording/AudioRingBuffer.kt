package com.example.varcamera.recording

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.util.Log
import com.example.varcamera.diag.DiagnosticLog
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.log10

/**
 * Keeps the last [KEEP_SECONDS] of the camera's HDMI sound in memory.
 *
 * The capture card carries the sound on its own USB audio interface, separate from
 * the video interface [com.example.varcamera.uvc.UvcCamera] claims. Android's USB
 * audio driver owns that interface, so the card shows up as a USB microphone and
 * [AudioRecord] reads it. Nothing else is used as a fallback: the tablet's own mic
 * would record the wrong place.
 *
 * Each 20 ms block is stamped on System.nanoTime(), the clock the video frames are
 * stamped on, so a clip's sound can be cut to exactly its frames. Uncompressed 48 kHz
 * stereo is 192 KB/s, under 9 MB for the whole window.
 *
 * Clip file ([FILE_NAME]): [MAGIC: Int][sample rate: Int][channels: Int]
 * [first sample time ns: Long], then continuous 16-bit little-endian PCM.
 */
class AudioRingBuffer(context: Context) {

    companion object {
        private const val TAG = "AudioRingBuffer"
        const val FILE_NAME = "audio.pcm"
        const val MAGIC = 0x56415241 // "VARA"
        const val HEADER_BYTES = 20

        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 2 * CHANNELS

        private const val KEEP_SECONDS = 45
        private const val BLOCK_FRAMES = SAMPLE_RATE / 50
        private const val BLOCK_BYTES = BLOCK_FRAMES * BYTES_PER_FRAME
        private const val BLOCK_NANOS = 20_000_000L
        private const val CAPACITY = KEEP_SECONDS * 50

        /** A hole longer than this (the card was replugged) is filled with silence. */
        private const val MAX_JITTER_NANOS = 40_000_000L
        private const val RETRY_MS = 1000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val lock = Any()
    private val data = ByteArray(CAPACITY * BLOCK_BYTES)
    private val stamps = LongArray(CAPACITY)
    private var stored = 0L

    @Volatile private var wanted = false
    @Volatile private var capturing = false
    @Volatile private var peak = 0
    private var thread: Thread? = null

    fun start() {
        if (wanted) return
        wanted = true
        thread = Thread({ captureLoop() }, "audio-capture").apply { start() }
    }

    /** Stops capturing; blocks captured so far stay available for a clip. */
    fun stop() {
        wanted = false
        thread?.join(1000)
        thread = null
    }

    /**
     * Recent peak on a 0..1 scale spanning -60..0 dBFS, or null when the card
     * delivers no sound at all (unplugged, or no audio interface).
     */
    fun level(): Float? {
        if (!capturing) return null
        val p = peak
        if (p <= 0) return 0f
        val db = 20 * log10(p / 32767f)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    /**
     * Writes the sound captured between [fromNanos] and [toNanos] into [target].
     * Returns false when no sound was captured in that span.
     */
    fun saveClip(target: File, fromNanos: Long, toNanos: Long): Boolean {
        val picked = ArrayList<Pair<Long, ByteArray>>()
        synchronized(lock) {
            val first = (stored - CAPACITY).coerceAtLeast(0L)
            for (n in first until stored) {
                val slot = (n % CAPACITY).toInt()
                val stamp = stamps[slot]
                if (stamp + BLOCK_NANOS < fromNanos || stamp > toNanos) continue
                picked += stamp to data.copyOfRange(slot * BLOCK_BYTES, (slot + 1) * BLOCK_BYTES)
            }
        }
        if (picked.isEmpty()) return false

        val startNanos = picked.first().first
        val silence = ByteArray(BLOCK_BYTES)
        DataOutputStream(BufferedOutputStream(FileOutputStream(File(target, FILE_NAME)), 64 * 1024)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(SAMPLE_RATE)
            out.writeInt(CHANNELS)
            out.writeLong(startNanos)

            // Blocks go end to end; only a real hole in capture is padded, so the
            // timeline stays true without chasing timestamp jitter.
            var framesWritten = 0L
            for ((stamp, block) in picked) {
                val expected = startNanos + framesWritten * 1_000_000_000L / SAMPLE_RATE
                var gapFrames = if (stamp - expected > MAX_JITTER_NANOS) {
                    (stamp - expected) * SAMPLE_RATE / 1_000_000_000L
                } else 0L
                framesWritten += gapFrames
                while (gapFrames > 0) {
                    val frames = gapFrames.coerceAtMost(BLOCK_FRAMES.toLong()).toInt()
                    out.write(silence, 0, frames * BYTES_PER_FRAME)
                    gapFrames -= frames
                }
                out.write(block)
                framesWritten += BLOCK_FRAMES
            }
        }
        Log.d(TAG, "saved ${picked.size} blocks of sound into ${target.name}")
        return true
    }

    private fun captureLoop() {
        while (wanted) {
            val device = findCardInput()
            val record = device?.let { open(it) }
            if (record == null) {
                capturing = false
                sleepQuietly(RETRY_MS)
                continue
            }
            try {
                record.startRecording()
                readUntilLost(record)
            } catch (e: Exception) {
                DiagnosticLog.log("Audio", "capture failed: ${e.message}")
            } finally {
                capturing = false
                peak = 0
                runCatching { record.stop() }
                record.release()
            }
            if (wanted) sleepQuietly(RETRY_MS)
        }
    }

    private fun readUntilLost(record: AudioRecord) {
        val block = ByteArray(BLOCK_BYTES)
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        var blocksSinceCheck = 0
        var loggedClock = false

        while (wanted) {
            val bytes = record.read(block, 0, BLOCK_BYTES)
            if (bytes <= 0) {
                DiagnosticLog.log("Audio", "read returned $bytes; card gone?")
                return
            }
            if (bytes < BLOCK_BYTES) block.fill(0, bytes)

            // Prefer the driver's capture time; otherwise assume the block just arrived.
            val hasTimestamp = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) ==
                AudioRecord.SUCCESS
            val firstSampleNanos = if (hasTimestamp) {
                timestamp.nanoTime + (framesRead - timestamp.framePosition) * 1_000_000_000L / SAMPLE_RATE
            } else {
                System.nanoTime() - BLOCK_NANOS
            }
            if (!loggedClock) {
                DiagnosticLog.log("Audio", "capturing from ${record.routedDevice?.productName}, driver timestamps: $hasTimestamp")
                loggedClock = true
            }
            framesRead += bytes / BYTES_PER_FRAME
            store(block, firstSampleNanos)

            // Unplugging the card would silently reroute to the tablet's own microphone.
            if (++blocksSinceCheck >= 50) {
                blocksSinceCheck = 0
                if (!isCard(record.routedDevice)) {
                    DiagnosticLog.log("Audio", "input rerouted away from the capture card")
                    return
                }
            }
        }
    }

    private fun store(block: ByteArray, firstSampleNanos: Long) {
        var blockPeak = 0
        var i = 0
        while (i < BLOCK_BYTES) {
            val sample = (block[i].toInt() and 0xFF) or (block[i + 1].toInt() shl 8)
            val magnitude = abs(sample.toShort().toInt())
            if (magnitude > blockPeak) blockPeak = magnitude
            i += 2
        }
        // Falls back over ~0.3 s, like a hardware meter.
        peak = maxOf(blockPeak, peak * 85 / 100)

        synchronized(lock) {
            val slot = (stored % CAPACITY).toInt()
            System.arraycopy(block, 0, data, slot * BLOCK_BYTES, BLOCK_BYTES)
            stamps[slot] = firstSampleNanos
            stored++
        }
        capturing = true
    }

    private fun findCardInput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isCard(it) }

    private fun isCard(device: AudioDeviceInfo?): Boolean =
        device != null && (device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET)

    private fun open(device: AudioDeviceInfo): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        return try {
            @Suppress("MissingPermission") // granted at startup by MainActivity
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, BLOCK_BYTES * 10))
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            record.setPreferredDevice(device)
            record
        } catch (e: Exception) {
            DiagnosticLog.log("Audio", "could not open ${device.productName}: ${e.message}")
            null
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
