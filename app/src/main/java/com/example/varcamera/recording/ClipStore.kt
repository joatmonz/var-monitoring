package com.example.varcamera.recording

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved review clips: one directory per REVIEW press, holding raw MJPEG chunks.
 * Oldest clips are dropped past [maxClips], or earlier when storage runs low.
 */
class ClipStore(
    context: Context,
    private val maxClips: Int = 20,
    private val reserveBytes: Long = 1024L * 1024 * 1024
) {
    private val root = context.getExternalFilesDir(null) ?: context.filesDir
    private val clipsDir = File(root, "clips").apply { mkdirs() }

    /** Scratch space for the rolling buffer, on the same filesystem so clips move instantly. */
    val liveBufferDir = File(root, "live_buffer")

    init {
        // Leftovers from the earlier H.264 recorder, which this store replaced.
        File(root, "var_segments").deleteRecursively()
        list().filter { it.listFiles().isNullOrEmpty() }.forEach { it.deleteRecursively() }
    }

    fun newClipDir(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var dir = File(clipsDir, "clip_$stamp")
        var suffix = 1
        while (dir.exists()) dir = File(clipsDir, "clip_${stamp}_${suffix++}")
        return dir
    }

    /** Oldest first. */
    fun list(): List<File> =
        clipsDir.listFiles { file -> file.isDirectory && file.name.startsWith("clip_") }
            ?.sortedBy { it.name }
            .orEmpty()

    fun hasClips(): Boolean = list().isNotEmpty()

    fun delete(clip: File) {
        clip.deleteRecursively()
    }

    fun enforceLimits() {
        val clips = list().toMutableList()
        while (clips.size > maxClips) clips.removeAt(0).deleteRecursively()
        while (clips.size > 1 && clipsDir.usableSpace < reserveBytes) clips.removeAt(0).deleteRecursively()
    }

    fun freeSpaceMB(): Long = clipsDir.usableSpace / (1024 * 1024)

    fun isStorageLow(): Boolean = clipsDir.usableSpace < reserveBytes
}
