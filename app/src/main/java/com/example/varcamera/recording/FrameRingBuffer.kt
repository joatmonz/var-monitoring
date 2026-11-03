package com.example.varcamera.recording

import android.util.Log
import com.example.varcamera.diag.DiagnosticLog
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
 * Saving a clip moves the chunks into the clip folder and leaves the buffer running:
 * the match goes on while the referee reviews, and the next REVIEW must again get a full
 * window. Moving is instant, where copying 45 s of 1080p took 15-20 s on a nearly full
 * phone. The buffer keeps pointing at the moved chunks (they never change again), and
 * a REVIEW soon after copies only the chunks the previous clip already took.
 *
 * Chunk layout, repeated per frame: [timestamp ns: Long][length: Int][JPEG bytes].
 */
class FrameRingBuffer(
    private val liveDir: File,
    private val keepSeconds: Int = 45
) {
    companion object {
        private const val TAG = "FrameRingBuffer"
        private const val CHUNK_NANOS = 1_000_000_000L
        const val CHUNK_EXTENSION = ".mjpg"
    }

    private sealed class Item {
        class Frame(val timestampNanos: Long, val jpeg: ByteArray) : Item()
        class Snapshot(val target: File) : Item() {
            val done = CountDownLatch(1)
            @Volatile var moved = 0
            /** Chunks an earlier clip already holds: source in that clip, destination here. */
            @Volatile var toCopy: List<Pair<File, File>> = emptyList()
        }
        object Reset : Item()
    }

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
     * Puts the buffered window into [target] while recording carries on.
     * Blocks until done (moving is instant; copying, only after a recent REVIEW, takes
     * longer); call off the main thread. Returns null if nothing was buffered.
     */
    fun saveClip(target: File): File? {
        val started = System.nanoTime()
        val snapshot = Item.Snapshot(target)
        queue.put(snapshot)
        if (!snapshot.done.await(5, TimeUnit.SECONDS)) return null

        var copied = 0
        snapshot.toCopy.forEach { (source, destination) ->
            runCatching { copy(source, destination); copied++ }
                .onFailure { Log.w(TAG, "could not copy ${source.name}: ${it.message}") }
        }
        DiagnosticLog.log("Rec", "clip ${target.name}: ${snapshot.moved} chunks moved, $copied copied in " +
            "${(System.nanoTime() - started) / 1_000_000} ms")
        return if (snapshot.moved + copied > 0) target else null.also { target.deleteRecursively() }
    }

    /** Chunks still in the live folder belong to the buffer; moved ones belong to a clip. */
    private fun isLive(file: File) = file.parentFile == liveDir

    /** Runs on the writer thread, so the chunk list never changes under it. */
    private fun takeSnapshot(snapshot: Item.Snapshot) {
        // Closing the open chunk makes its frames part of the clip.
        closeChunk()
        unsaved = false
        val window = completed.toList().takeLast(keepSeconds + 1)
        if (window.isEmpty()) return
        snapshot.target.mkdirs()
        val toCopy = mutableListOf<Pair<File, File>>()
        window.forEach { chunk ->
            val destination = File(snapshot.target, chunk.name)
            if (isLive(chunk) && chunk.renameTo(destination)) {
                completed[completed.indexOf(chunk)] = destination
                snapshot.moved++
            } else {
                toCopy += chunk to destination
            }
        }
        snapshot.toCopy = toCopy
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
                            completed.filter(::isLive).forEach { it.delete() }
                            completed.clear()
                            completedCount = 0
                            unsaved = false
                        }
                        is Item.Snapshot -> {
                            takeSnapshot(item)
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
        while (completed.size > keepSeconds + 1) {
            val old = completed.removeFirst()
            if (isLive(old)) old.delete()
        }
        completedCount = completed.size.coerceAtMost(keepSeconds + 1)
    }
}
