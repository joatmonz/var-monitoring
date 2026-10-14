package com.example.varcamera.review

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import kotlin.math.abs

/**
 * Plays a clip's sound in step with the picture.
 *
 * The picture keeps its own clock — frame steps, loops and speed changes all act on
 * it — so the sound follows the picture: it starts from wherever the picture is, and
 * when the two drift apart the sound is nudged back into place.
 *
 * Driven by a single thread calling [feed] once per 20 ms block; [feed] blocks while
 * the track's buffer is full, which paces that thread.
 */
internal class ClipAudioPlayer {

    companion object {
        private const val TAG = "ClipAudioPlayer"
        private const val BLOCK_MS = 20

        /** Drift under this is not noticeable; beyond it the sound is realigned. */
        private const val MAX_DRIFT_MS = 30L

        /** A jump this large means a seek or a signal gap in the clip, not drift. */
        private const val RESTART_DRIFT_MS = 250L

        /** Time for a correction to reach the speaker before it is judged again. */
        private const val SETTLE_NANOS = 400_000_000L

        /**
         * Output latency, learned from corrections and applied to every later start.
         * Kept across review sessions: it belongs to the tablet, not the clip.
         */
        @Volatile private var latencyFrames = 0L
    }

    private var track: AudioTrack? = null
    private var source: ClipAudio? = null
    private var epoch = -1
    private var buffer = ByteArray(0)
    private val timestamp = AudioTimestamp()

    /** Clip sample frame = track position + [base]; moves when sound is skipped or padded. */
    private var base = 0L
    private var nextFrame = 0L
    private var settleUntil = 0L

    /**
     * Queues the next block. [wantFrame] is the sample that belongs to the picture on
     * screen right now; [playEpoch] changes whenever the picture jumps.
     */
    fun feed(audio: ClipAudio, playEpoch: Int, wantFrame: Long) {
        if (audio !== source || playEpoch != epoch || track == null) {
            restart(audio, playEpoch, wantFrame)
        } else if (System.nanoTime() >= settleUntil) {
            realign(audio, wantFrame)
        }
        val t = track ?: return
        audio.read(nextFrame, buffer)
        t.write(buffer, 0, buffer.size)
        nextFrame += buffer.size / audio.bytesPerFrame
    }

    /** Silences at once, dropping whatever is queued. */
    fun stop() {
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
            it.release()
        }
        track = null
        source = null
        epoch = -1
    }

    private fun restart(audio: ClipAudio, playEpoch: Int, wantFrame: Long) {
        stop()
        val t = createTrack(audio) ?: return
        track = t
        source = audio
        epoch = playEpoch
        nextFrame = wantFrame + latencyFrames
        base = nextFrame
        settleUntil = System.nanoTime() + SETTLE_NANOS
        t.play()
    }

    private fun realign(audio: ClipAudio, wantFrame: Long) {
        val t = track ?: return
        if (!t.getTimestamp(timestamp) || timestamp.framePosition <= 0) return

        val heardNow = base + timestamp.framePosition +
            (System.nanoTime() - timestamp.nanoTime) * audio.sampleRate / 1_000_000_000L
        val drift = wantFrame - heardNow // > 0: the sound is late
        val driftMs = drift * 1000 / audio.sampleRate
        if (abs(driftMs) <= MAX_DRIFT_MS) return

        latencyFrames = (latencyFrames + drift).coerceIn(-audio.sampleRate / 2L, audio.sampleRate.toLong())
        Log.d(TAG, "sound off by $driftMs ms; output latency now ${latencyFrames * 1000 / audio.sampleRate} ms")
        if (abs(driftMs) > RESTART_DRIFT_MS) {
            restart(audio, epoch, wantFrame)
            return
        }
        if (drift > 0) {
            nextFrame += drift
        } else {
            writeSilence(t, -drift, audio.bytesPerFrame)
        }
        base += drift
        settleUntil = System.nanoTime() + SETTLE_NANOS
    }

    private fun writeSilence(t: AudioTrack, frames: Long, bytesPerFrame: Int) {
        val silence = ByteArray(buffer.size)
        var left = frames * bytesPerFrame
        while (left > 0) {
            val bytes = left.coerceAtMost(silence.size.toLong()).toInt()
            t.write(silence, 0, bytes)
            left -= bytes
        }
    }

    private fun createTrack(audio: ClipAudio): AudioTrack? {
        val channelMask = if (audio.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val blockBytes = audio.sampleRate * BLOCK_MS / 1000 * audio.bytesPerFrame
        if (buffer.size != blockBytes) buffer = ByteArray(blockBytes)
        val minBuffer = AudioTrack.getMinBufferSize(audio.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(audio.sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, blockBytes * 4))
                .build()
        }.onFailure { Log.w(TAG, "could not open sound output: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.state == AudioTrack.STATE_INITIALIZED || run { it.release(); false } }
    }
}
