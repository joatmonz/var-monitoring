package com.example.varcamera.recording

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Keeps the last [keepSeconds] of the capture card's MJPEG frames on disk, untouched.
 *
 * Frames are stored exactly as the card sent them — no re-encoding, so a saved clip
 * has the card's full 1080p50 quality and saving costs no CPU. They go into
 * one-second chunk files so that old footage is dropped by deleting whole files.
 *
 * Saving a clip copies the chunks and leaves the buffer running: the match goes on
 * while the referee reviews, and the next REVIEW must again get a full window.
 *
 * Chunk layout, repeated per frame: [timestamp ns: Long][length: Int][JPEG bytes].
 */
class FrameRingBuffer(
    private val liveDir: File,
    private val keepSeconds: Int = 30
) {
    companion object {
        private const val TAG = "FrameRingBuffer"
        private const val CHUNK_NANOS = 1_000_000_000L
        const val CHUNK_EXTENSION = ".mjpg"
    }

    private sealed class Item {
        class Frame(val timestampNanos: Long, val jpeg: ByteArray) : Item()
        class Snapshot : Item() {
            val done = CountDownLatch(1)
            @Volatile var chunks: List<File> = emptyList()
        }
        object Reset : Item()
    }

    /** While > 0 the writer keeps old chunks, so a copy in progress never loses its source. */
    private val pins = java.util.concurrent.atomic.AtomicInteger()

    // Bounded so a slow disk drops frames instead of exhausting memory (~3s at 50fps).
    private val queue = LinkedBlockingQueue<Item>(150)

    @Volatile var isRecording = false
        private set
    @Volatile private var running = true
    @Volatile private var completedCount = 0
    @Volatile private var currentHasFrames = false

    // Writer-thread state.
    private val completed = ArrayDeque<File>()
    private var output: DataOutputStream? = null
    private var currentFile: File? = null
    private var chunkStartNanos = 0L
    private var sequence = 0

    init {
        liveDir.mkdirs()
        liveDir.listFiles()?.forEach { it.delete() }
    }

    private val writer = Thread({ writeLoop() }, "ring-writer").apply { start() }

    /** Starts a fresh buffer; anything from before a pause would leave a gap in the clip. */
    fun start() {
        queue.put(Item.Reset)
        isRecording = true
    }

    /** Stops adding frames but keeps what is buffered, so REVIEW still works. */
    fun stop() {
        isRecording = false
    }

    fun append(jpeg: ByteArray) {
        if (isRecording) queue.offer(Item.Frame(System.nanoTime(), jpeg))
    }

    fun bufferedSeconds(): Int = completedCount.coerceAtMost(keepSeconds)

    fun hasFrames(): Boolean = completedCount > 0 || currentHasFrames

    /** Footage recorded since the last saved clip; without it a save would only duplicate. */
    fun hasUnsavedFrames(): Boolean = unsaved

    @Volatile private var unsaved = false

    /**
     * Copies the buffered window into [target] while recording carries on.
     * Blocks until done (a few seconds for 30s of 1080p); call off the main thread.
     * Returns null if nothing was buffered.
     */
    fun saveClip(target: File): File? {
        pins.incrementAndGet()
        try {
            val snapshot = Item.Snapshot()
            queue.put(snapshot)
            if (!snapshot.done.await(5, TimeUnit.SECONDS) || snapshot.chunks.isEmpty()) return null

            target.mkdirs()
            var copied = 0
            snapshot.chunks.forEach { chunk ->
                runCatching { copy(chunk, File(target, chunk.name)); copied++ }
                    .onFailure { Log.w(TAG, "could not copy ${chunk.name}: ${it.message}") }
            }
            Log.d(TAG, "saved $copied chunks into ${target.name}")
            return if (copied > 0) target else null.also { target.deleteRecursively() }
        } finally {
            pins.decrementAndGet()
        }
    }

    private fun copy(source: File, destination: File) {
        java.io.FileInputStream(source).channel.use { input ->
            FileOutputStream(destination).channel.use { output ->
                var position = 0L
                val size = input.size()
                while (position < size) position += input.transferTo(position, size - position, output)
            }
        }
    }

    fun release() {
        isRecording = false
        running = false
        writer.interrupt()
        writer.join(2000)
        liveDir.listFiles()?.forEach { it.delete() }
    }

    private fun writeLoop() {
        try {
            while (running) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    when (item) {
                        is Item.Frame -> write(item)
                        is Item.Reset -> {
                            closeChunk()
                            completed.forEach { it.delete() }
                            completed.clear()
                            completedCount = 0
                            unsaved = false
                        }
                        is Item.Snapshot -> {
                            // Closing the open chunk makes its frames part of the clip.
                            closeChunk()
                            item.chunks = completed.toList().takeLast(keepSeconds + 1)
                            unsaved = false
                            item.done.countDown()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "buffer write failed", e)
                    if (item is Item.Snapshot) item.done.countDown()
                }
            }
        } catch (_: InterruptedException) {
            // released
        } finally {
            runCatching { output?.close() }
        }
    }

    private fun write(frame: Item.Frame) {
        if (output == null || frame.timestampNanos - chunkStartNanos >= CHUNK_NANOS) {
            closeChunk()
            val file = File(liveDir, "chunk_%06d$CHUNK_EXTENSION".format(sequence++))
            output = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 256 * 1024))
            currentFile = file
            chunkStartNanos = frame.timestampNanos
        }
        output!!.apply {
            writeLong(frame.timestampNanos)
            writeInt(frame.jpeg.size)
            write(frame.jpeg)
        }
        currentHasFrames = true
        unsaved = true
    }

    private fun closeChunk() {
        val stream = output ?: return
        stream.close()
        output = null
        currentFile?.let { file ->
            if (currentHasFrames) completed.addLast(file) else file.delete()
        }
        currentFile = null
        currentHasFrames = false

        // One chunk more than the window, so a clip always spans at least keepSeconds.
        if (pins.get() == 0) {
            while (completed.size > keepSeconds + 1) completed.removeFirst().delete()
        }
        completedCount = completed.size.coerceAtMost(keepSeconds + 1)
    }
}
