package com.example.varcamera.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Saving a clip moves the buffer's chunk files instead of copying them. These checks
 * make sure that stays safe: clips keep their footage while the buffer runs on, and
 * a REVIEW right after another still gets the whole window.
 */
class FrameRingBufferTest {

    @get:Rule val folder = TemporaryFolder()

    private val live by lazy { folder.newFolder("live_buffer") }
    private val clips by lazy { folder.newFolder("clips") }

    /** Feeds [seconds] of 10 fps "frames"; the buffer cuts a chunk per second of timestamps. */
    private fun FrameRingBuffer.feed(seconds: Int) {
        repeat(seconds * 10) {
            append(ByteArray(1000) { 7 })
            Thread.sleep(100)
        }
    }

    private fun chunksIn(dir: File) = dir.listFiles { f -> f.name.endsWith(FrameRingBuffer.CHUNK_EXTENSION) }!!.size

    @Test
    fun clipsMoveChunksAndSurviveTheBufferRunningOn() {
        val buffer = FrameRingBuffer(live, keepSeconds = 3)
        buffer.start()
        buffer.feed(5)

        val first = buffer.saveClip(File(clips, "clip_1"))!!
        val firstChunks = chunksIn(first)
        assertTrue("first clip has the window", firstChunks in 3..5)

        // Right away again: the chunks now live in clip 1, so clip 2 must copy them.
        val second = buffer.saveClip(File(clips, "clip_2"))!!
        assertEquals(firstChunks, chunksIn(second))

        // The buffer moves past both clips; it must not delete the clips' files.
        buffer.feed(6)
        assertEquals(firstChunks, chunksIn(first))
        assertEquals(firstChunks, chunksIn(second))
        assertTrue("live folder stays bounded", chunksIn(live) <= 5)

        val third = buffer.saveClip(File(clips, "clip_3"))!!
        assertTrue(chunksIn(third) in 3..5)
        buffer.release()
    }
}
