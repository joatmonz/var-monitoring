package com.example.varcamera.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Plays saved MJPEG clips at full resolution for the referee review.
 *
 * Every frame is an independent JPEG, so seeking and frame stepping are exact and
 * instant. One 1080p frame takes ~19ms to decode on the target tablet — just
 * under the 20ms a frame lasts at 50fps — so two decoder threads work ahead of the
 * playhead while a display thread shows whichever frame is due.
 *
 * Threading: the UI thread only changes playback state under [lock]. The display
 * thread alone switches clips and evicts cached frames, so a bitmap is never
 * recycled or reused while it is being drawn.
 */
class ReviewPlayerManager {

    companion object {
        private const val TAG = "ReviewPlayer"
        private const val DECODER_THREADS = 2
        private const val LOOKAHEAD_FRAMES = 4

        /** A new clip opens on its final seconds: the incident is what just happened. */
        private const val OPEN_BEFORE_END_MS = 15_000L

        /** Default reach of the loop either side of a mark. */
        const val DEFAULT_MARK_SPAN_MS = 3_000L
    }

    /** What the review HUD shows about the clip on screen; [audioRate] is 0 without sound. */
    data class ClipInfo(val width: Int, val height: Int, val fps: Int, val audioRate: Int)

    /**
     * The part of the clip LOOP plays. [inMs]/[outMs] are the whole clip unless set;
     * [markMs] is -1 without a mark.
     */
    data class LoopRange(
        val inMs: Long,
        val outMs: Long,
        val inSet: Boolean,
        val outSet: Boolean,
        val markMs: Long
    ) {
        val isWholeClip: Boolean get() = !inSet && !outSet && markMs < 0
    }

    var clips: List<File> = emptyList()
        private set

    @Volatile private var view: ClipView? = null
    @Volatile private var running = true

    private val lock = Object()

    // Guarded by lock.
    private var clip: MjpegClip? = null
    private var audio: ClipAudio? = null
    private var clipIndex = 0
    private var generation = 0
    private val cache = HashMap<Int, Bitmap>()
    private val inProgress = HashSet<Int>()
    private var playing = false
    private var speed = 1f
    private var looping = false
    private var anchorMediaMs = 0L
    private var anchorWallNanos = 0L
    private var pausedIndex = 0
    private var targetIndex = 0
    private var scrubTargetMs = -1L
    private var markMs = -1L
    private var markSpanMs = DEFAULT_MARK_SPAN_MS
    private var inMs = -1L // -1: clip start
    private var outMs = -1L // -1: clip end
    private var pendingClip = -1
    private var pendingOpenAtEnd = false
    private var pendingAutoPlay = false
    private var soundOn = true

    /** Bumped on every jump of the playhead, so the sound restarts from the new place. */
    private var audioEpoch = 0

    private var displayedIndex = -1
    private val pool = LinkedBlockingQueue<Bitmap>()

    private val threads = buildList {
        add(Thread({ displayLoop() }, "review-display"))
        add(Thread({ audioLoop() }, "review-audio"))
        repeat(DECODER_THREADS) { add(Thread({ decodeLoop() }, "review-decode-$it")) }
    }.onEach { it.start() }

    fun attachView(clipView: ClipView) {
        view = clipView
        synchronized(lock) { lock.notifyAll() }
    }

    fun loadClips(files: List<File>) = synchronized(lock) {
        clips = files
        pendingClip = files.lastIndex
        pendingOpenAtEnd = true
        pendingAutoPlay = false
        lock.notifyAll()
    }

    fun jumpToClip(index: Int) = synchronized(lock) {
        if (index !in clips.indices) return@synchronized
        pendingClip = index
        pendingOpenAtEnd = false
        pendingAutoPlay = false
        lock.notifyAll()
    }

    fun currentClipIndex(): Int = synchronized(lock) { clipIndex }

    /** Drops a clip from the list; the caller deletes its files. */
    fun removeClip(index: Int): File? = synchronized(lock) {
        if (index !in clips.indices) return@synchronized null
        val removed = clips[index]
        clips = clips.toMutableList().apply { removeAt(index) }
        when {
            index < clipIndex -> clipIndex--
            index == clipIndex -> {
                pendingClip = if (clips.isEmpty()) -2 else index.coerceAtMost(clips.lastIndex)
                pendingOpenAtEnd = false
                pendingAutoPlay = false
            }
        }
        lock.notifyAll()
        removed
    }

    fun togglePlayPause() = synchronized(lock) {
        if (playing) pauseLocked() else playLocked()
        lock.notifyAll()
    }

    fun isPlaying(): Boolean = synchronized(lock) { playing }

    fun stepForward() = synchronized(lock) {
        val c = clip ?: return@synchronized
        pauseLocked()
        pausedIndex = (targetIndex + 1).coerceAtMost(c.frameCount - 1)
        lock.notifyAll()
    }

    fun stepBackward() = synchronized(lock) {
        pauseLocked()
        pausedIndex = (targetIndex - 1).coerceAtLeast(0)
        lock.notifyAll()
    }

    fun seekBackSeconds(seconds: Int) = synchronized(lock) {
        seekLocked(currentMsLocked() - seconds * 1000L)
    }

    fun seekForwardSeconds(seconds: Int) = synchronized(lock) {
        seekLocked(currentMsLocked() + seconds * 1000L)
    }

    fun setSpeed(newSpeed: Float) = synchronized(lock) {
        if (playing) reanchorLocked(currentMsLocked())
        speed = newSpeed
    }

    fun toggleLoop(): Boolean = synchronized(lock) {
        looping = !looping
        if (looping) {
            val (inMs, outMs) = loopRangeLocked()
            val now = currentMsLocked()
            if (now < inMs || now > outMs) seekLocked(inMs)
        }
        looping
    }

    /**
     * Marks the contact moment at the playhead and puts In and Out the mark span
     * either side of it — the quick way to a loop around an incident.
     */
    fun setMark() = synchronized(lock) {
        if (clip == null) return@synchronized
        markMs = currentMsLocked()
        applyMarkSpanLocked()
        if (looping) seekLocked(loopRangeLocked().first)
    }

    /** In at the frame on screen. An Out at or before it is dropped. */
    fun setIn() = synchronized(lock) {
        if (clip == null) return@synchronized
        val now = currentMsLocked()
        inMs = now
        if (outMs in 0..now) outMs = -1L
        lock.notifyAll()
    }

    /** Out just after the frame on screen, so that frame is the last one the loop plays. */
    fun setOut() = synchronized(lock) {
        val c = clip ?: return@synchronized
        val end = if (targetIndex >= c.frameCount - 1) c.durationMs else c.timeMs(targetIndex + 1)
        outMs = end
        if (inMs >= end) inMs = -1L
        lock.notifyAll()
    }

    /** Back to the whole clip: no mark, In or Out. */
    fun clearRange() = synchronized(lock) {
        markMs = -1L
        inMs = -1L
        outMs = -1L
    }

    /** How far In and Out sit either side of the mark; moves them if a mark is set. */
    fun setMarkSpan(spanMs: Long) = synchronized(lock) {
        markSpanMs = spanMs
        if (markMs >= 0) applyMarkSpanLocked()
        lock.notifyAll()
    }

    fun clipInfo(): ClipInfo? = synchronized(lock) {
        val c = clip ?: return@synchronized null
        ClipInfo(c.frameWidth, c.frameHeight, c.averageFps, audio?.sampleRate ?: 0)
    }

    /** Sound plays with 1x playback only; slowed or sped-up sound would not help a decision. */
    fun setSoundOn(on: Boolean) = synchronized(lock) {
        soundOn = on
        lock.notifyAll()
    }

    fun loopRange(): LoopRange = synchronized(lock) {
        val (start, end) = loopRangeLocked()
        LoopRange(start, end, inSet = inMs >= 0, outSet = outMs >= 0, markMs = markMs)
    }

    /** Playhead as "m:ss.ff" — second and frame within that second. */
    fun displayTimecode(): String = synchronized(lock) {
        val c = clip ?: return@synchronized "0:00.00"
        c.timecode(if (scrubTargetMs >= 0) c.indexAt(scrubTargetMs) else targetIndex)
    }

    fun durationTimecode(): String = synchronized(lock) {
        val c = clip ?: return@synchronized "0:00.00"
        c.timecode(c.frameCount - 1)
    }

    /** While a finger is on the timeline, playback holds and follows the drag. */
    fun setScrubbing(active: Boolean) = synchronized(lock) {
        val c = clip ?: return@synchronized
        if (active) {
            pauseLocked()
            scrubTargetMs = c.timeMs(targetIndex)
        } else if (scrubTargetMs >= 0) {
            pausedIndex = c.indexAt(scrubTargetMs)
            scrubTargetMs = -1L
        }
        lock.notifyAll()
    }

    /** Moves a scrub in progress to an absolute position, for the overview strip. */
    fun scrubTo(ms: Long) = synchronized(lock) {
        val c = clip ?: return@synchronized
        if (scrubTargetMs < 0) return@synchronized
        scrubTargetMs = ms.coerceIn(0L, c.durationMs)
        lock.notifyAll()
    }

    fun seekByMs(deltaMs: Long) = synchronized(lock) {
        val c = clip ?: return@synchronized
        if (scrubTargetMs >= 0) {
            scrubTargetMs = (scrubTargetMs + deltaMs).coerceIn(0L, c.durationMs)
            lock.notifyAll()
        } else {
            seekLocked(currentMsLocked() + deltaMs)
        }
    }

    fun getCurrentPositionMs(): Long = synchronized(lock) { currentMsLocked() }

    fun displayPositionMs(): Long = synchronized(lock) {
        if (scrubTargetMs >= 0) scrubTargetMs else currentMsLocked()
    }

    fun getDurationMs(): Long = synchronized(lock) { clip?.durationMs ?: 0L }

    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        threads.forEach { it.join(1000) }
        synchronized(lock) {
            clip?.close()
            clip = null
            audio?.close()
            audio = null
            cache.values.forEach { it.recycle() }
            cache.clear()
        }
        while (true) pool.poll()?.recycle() ?: break
        view = null
    }

    // ---------------------------------------------------------------- state helpers

    private fun currentMsLocked(): Long = clip?.timeMs(targetIndex) ?: 0L

    private fun loopRangeLocked(): Pair<Long, Long> {
        val duration = clip?.durationMs ?: 0L
        return (if (inMs >= 0) inMs else 0L) to (if (outMs >= 0) outMs else duration)
    }

    private fun applyMarkSpanLocked() {
        val duration = clip?.durationMs ?: 0L
        inMs = (markMs - markSpanMs).coerceAtLeast(0L)
        outMs = (markMs + markSpanMs).coerceAtMost(duration)
    }

    private fun pauseLocked() {
        if (!playing) return
        playing = false
        pausedIndex = targetIndex
    }

    private fun playLocked() {
        val c = clip ?: return
        if (pausedIndex >= c.frameCount - 1) pausedIndex = 0
        reanchorLocked(c.timeMs(pausedIndex))
        playing = true
    }

    private fun reanchorLocked(mediaMs: Long) {
        anchorMediaMs = mediaMs
        anchorWallNanos = System.nanoTime()
        audioEpoch++
    }

    /** Where playback is right now, between frames too; the sound needs finer steps than 20 ms. */
    private fun playbackMsLocked(): Long =
        anchorMediaMs + ((System.nanoTime() - anchorWallNanos) / 1e6 * speed).toLong()

    private fun seekLocked(ms: Long) {
        val c = clip ?: return
        val index = c.indexAt(ms.coerceIn(0L, c.durationMs))
        if (playing) reanchorLocked(c.timeMs(index)) else pausedIndex = index
        targetIndex = index
        lock.notifyAll()
    }

    private fun computeTargetLocked(c: MjpegClip): Int {
        if (scrubTargetMs >= 0) return c.indexAt(scrubTargetMs)
        if (!playing) return pausedIndex.coerceIn(0, c.frameCount - 1)

        val mediaMs = playbackMsLocked()
        if (looping) {
            val (inMs, outMs) = loopRangeLocked()
            if (mediaMs < inMs || mediaMs >= outMs) {
                reanchorLocked(inMs)
                return c.indexAt(inMs)
            }
            return c.indexAt(mediaMs)
        }
        if (mediaMs < c.durationMs) return c.indexAt(mediaMs)
        playing = false
        pausedIndex = c.frameCount - 1
        return pausedIndex
    }

    // ---------------------------------------------------------------- threads

    private fun displayLoop() {
        while (running) {
            switchClipIfRequested()

            val currentView = view
            var toDraw: Bitmap? = null
            var drawIndex = -1

            synchronized(lock) {
                val c = clip
                if (c == null) {
                    if (currentView?.consumeRedrawRequest() == true) currentView.render(null)
                    lock.wait(20)
                    return@synchronized
                }
                targetIndex = computeTargetLocked(c)
                val redraw = currentView?.consumeRedrawRequest() == true
                val ready = cache[targetIndex]
                if (ready != null && (targetIndex != displayedIndex || redraw)) {
                    toDraw = ready
                    drawIndex = targetIndex
                } else if (redraw && displayedIndex >= 0) {
                    toDraw = cache[displayedIndex]
                    drawIndex = displayedIndex
                }
                evictLocked()
                lock.notifyAll()
            }

            if (toDraw != null && currentView != null) {
                currentView.render(toDraw)
                displayedIndex = drawIndex
            } else {
                synchronized(lock) { if (running) lock.wait(2) }
            }
        }
    }

    /** Keeps the frames around the playhead plus the one on screen; the rest go to the pool. */
    private fun evictLocked() {
        val first = targetIndex - 1
        val last = targetIndex + LOOKAHEAD_FRAMES
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val (index, bitmap) = iterator.next()
            if (index !in first..last && index != displayedIndex) {
                iterator.remove()
                pool.offer(bitmap)
            }
        }
    }

    private fun switchClipIfRequested() {
        val request = synchronized(lock) {
            val index = pendingClip
            pendingClip = -1
            index
        }
        if (request == -1) return

        val file = if (request >= 0) clips.getOrNull(request) else null
        val opened = file?.let { runCatching { MjpegClip.open(it) }.getOrNull() }
        val openedAudio = if (opened != null) file?.let { runCatching { ClipAudio.open(it) }.getOrNull() } else null

        val previousAudio: ClipAudio?
        val previous = synchronized(lock) {
            val old = clip
            previousAudio = audio
            clip = opened
            audio = openedAudio
            if (request >= 0) clipIndex = request
            generation++
            audioEpoch++
            inProgress.clear()
            cache.values.forEach { pool.offer(it) }
            cache.clear()
            displayedIndex = -1
            scrubTargetMs = -1L
            markMs = -1L
            inMs = -1L
            outMs = -1L
            playing = false
            pausedIndex = if (opened != null && pendingOpenAtEnd) {
                opened.indexAt(opened.durationMs - OPEN_BEFORE_END_MS)
            } else {
                0
            }
            targetIndex = pausedIndex
            if (opened != null && pendingAutoPlay) playLocked()
            lock.notifyAll()
            old
        }
        previous?.close()
        previousAudio?.close()
        if (opened == null) view?.render(null)
        Log.d(TAG, "opened ${file?.name} (${opened?.frameCount ?: 0} frames)")
    }

    private fun audioLoop() {
        val player = ClipAudioPlayer()
        try {
            while (running) {
                var source: ClipAudio? = null
                var epoch = 0
                var wantNanos = 0L
                synchronized(lock) {
                    val c = clip
                    val a = audio
                    if (c != null && a != null && soundOn && playing && scrubTargetMs < 0 &&
                        kotlin.math.abs(speed - 1f) < 0.01f
                    ) {
                        source = a
                        epoch = audioEpoch
                        wantNanos = c.captureNanos(playbackMsLocked())
                    } else if (running) {
                        lock.wait(20)
                    }
                }
                val a = source
                if (a == null) player.stop() else player.feed(a, epoch, a.frameAt(wantNanos))
            }
        } finally {
            player.stop()
        }
    }

    private fun decodeLoop() {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inMutable = true
        }

        while (running) {
            var job = -1
            var source: MjpegClip? = null
            var jobGeneration = 0

            synchronized(lock) {
                val c = clip
                if (c != null) {
                    for (step in 0 until LOOKAHEAD_FRAMES) {
                        val index = targetIndex + step
                        if (index >= c.frameCount) break
                        if (index !in cache && index !in inProgress) {
                            job = index
                            inProgress.add(index)
                            break
                        }
                    }
                }
                if (job < 0) {
                    if (running) lock.wait(10)
                } else {
                    source = c
                    jobGeneration = generation
                }
            }
            if (job < 0) continue

            val bitmap = runCatching { decode(source!!.readFrame(job), options) }
                .onFailure { Log.w(TAG, "frame $job decode failed: ${it.message}") }
                .getOrNull()

            synchronized(lock) {
                if (jobGeneration == generation) {
                    inProgress.remove(job)
                    if (bitmap != null) cache[job] = bitmap
                } else {
                    bitmap?.let { pool.offer(it) }
                }
                lock.notifyAll()
            }
        }
    }

    private fun decode(jpeg: ByteArray, options: BitmapFactory.Options): Bitmap? {
        options.inBitmap = pool.poll()
        return try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        } catch (e: IllegalArgumentException) {
            // The pooled bitmap does not fit this frame size.
            options.inBitmap?.recycle()
            options.inBitmap = null
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        }
    }
}
