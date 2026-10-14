package com.example.varcamera.uvc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicReference

/**
 * Draws incoming MJPEG frames straight to a SurfaceView.
 *
 * Only the newest frame is kept: if decoding falls behind the USB stream, older
 * frames are dropped rather than queued, which keeps on-screen latency bounded.
 */
class UvcPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "UvcPreviewView"

        /** Luminance spread below this means the frame is one flat colour. */
        private const val UNIFORM_TOLERANCE = 4

        /** Flat frames must persist this long (~0.7s at 30fps) before reporting no signal. */
        private const val BLANK_FRAMES_TO_REPORT = 20

        /** Consecutive genuinely moving frames needed before declaring the signal back. */
        private const val GOOD_FRAMES_TO_RECOVER = 5

        private const val SAMPLE_COLUMNS = 12
        private const val SAMPLE_ROWS = 8
    }

    /** Reports measured display rate and average JPEG decode cost, once per second. */
    var onStats: ((fps: Int, decodeMs: Int) -> Unit)? = null

    /**
     * Reports when the picture turns into a flat colour and back. Capture cards keep
     * streaming a blank frame when their HDMI input goes away, so an arriving frame
     * is not by itself proof that the camera is still on.
     */
    var onBlankChanged: ((isBlank: Boolean) -> Unit)? = null

    private val pending = AtomicReference<ByteArray?>()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val decodeOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inMutable = true
        // Live view only has to follow the play; full 1080p is kept for review.
        inSampleSize = 2
    }

    @Volatile private var running = false
    private var decodeThread: Thread? = null
    private var renderThread: Thread? = null

    // Decode and display run on separate threads so one frame decodes while the
    // previous one is drawn; serially they overrun the 20ms budget of 50fps.
    private val freeBitmaps = java.util.concurrent.LinkedBlockingQueue<Bitmap>()
    private val readyForDisplay = AtomicReference<Bitmap?>()
    private val displaySignal = Object()
    private val decodeNanos = java.util.concurrent.atomic.AtomicLong()
    private val decodeCount = java.util.concurrent.atomic.AtomicInteger()

    private var blankStreak = 0
    private var goodStreak = 0
    private var blankReported = false
    private val sample = IntArray(SAMPLE_COLUMNS * SAMPLE_ROWS)

    init {
        holder.addCallback(this)
    }

    fun submitFrame(jpeg: ByteArray) {
        pending.set(jpeg)
    }

    fun clear() {
        pending.set(null)
        resetSignalTracking()
        drawBlank()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        decodeThread = Thread({ decodeLoop() }, "uvc-decode").apply { start() }
        renderThread = Thread({ renderLoop() }, "uvc-render").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        synchronized(displaySignal) { displaySignal.notifyAll() }
        decodeThread?.join(1000)
        renderThread?.join(1000)
        decodeThread = null
        renderThread = null
        readyForDisplay.getAndSet(null)?.recycle()
        while (true) freeBitmaps.poll()?.recycle() ?: break
    }

    private fun decodeLoop() {
        while (running) {
            val jpeg = pending.getAndSet(null)
            if (jpeg == null) {
                Thread.sleep(1)
                continue
            }

            val start = System.nanoTime()
            val bitmap = decode(jpeg, freeBitmaps.poll()) ?: continue
            decodeNanos.addAndGet(System.nanoTime() - start)
            decodeCount.incrementAndGet()

            // Newest frame wins; one the renderer never picked up goes back to the pool.
            readyForDisplay.getAndSet(bitmap)?.let { freeBitmaps.offer(it) }
            synchronized(displaySignal) { displaySignal.notify() }
        }
    }

    private fun renderLoop() {
        var framesThisSecond = 0
        var windowStart = System.currentTimeMillis()

        while (running) {
            val bitmap = readyForDisplay.getAndSet(null)
            if (bitmap == null) {
                synchronized(displaySignal) { displaySignal.wait(5) }
                continue
            }

            drawFrame(bitmap)
            trackBlankFrames(bitmap)
            freeBitmaps.offer(bitmap)

            framesThisSecond++
            val now = System.currentTimeMillis()
            if (now - windowStart >= 1000) {
                val decoded = decodeCount.getAndSet(0).coerceAtLeast(1)
                val avgDecodeMs = (decodeNanos.getAndSet(0) / decoded / 1_000_000L).toInt()
                onStats?.invoke(framesThisSecond, avgDecodeMs)
                framesThisSecond = 0
                windowStart = now
            }
        }
    }

    private fun decode(jpeg: ByteArray, target: Bitmap?): Bitmap? {
        decodeOptions.inBitmap = target
        return try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions)
        } catch (e: IllegalArgumentException) {
            // Frame size changed, so the pooled bitmap no longer fits.
            target?.recycle()
            decodeOptions.inBitmap = null
            runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions) }
                .onFailure { Log.w(TAG, "decode failed: ${it.message}") }
                .getOrNull()
        }
    }

    private fun drawFrame(bitmap: Bitmap) {
        val canvas = lockCanvasCompat() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            fitCenter(bitmap.width, bitmap.height, canvas.width, canvas.height, dstRect)
            canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun trackBlankFrames(bitmap: Bitmap) {
        sampleLuminance(bitmap)

        var min = 255
        var max = 0
        for (value in sample) {
            if (value < min) min = value
            if (value > max) max = value
        }

        // Frozen pictures are detected upstream on the raw JPEG bytes: a coarse
        // luminance grid cannot tell a still scene from a repeated frame.
        val isFlat = max - min <= UNIFORM_TOLERANCE

        if (isFlat) blankStreak++ else blankStreak = 0
        if (isFlat) goodStreak = 0 else goodStreak++

        val noSignal = blankStreak >= BLANK_FRAMES_TO_REPORT
        if (!blankReported && noSignal) {
            blankReported = true
            onBlankChanged?.invoke(true)
        } else if (blankReported && goodStreak >= GOOD_FRAMES_TO_RECOVER) {
            blankReported = false
            onBlankChanged?.invoke(false)
        }
    }

    /** Coarse luminance grid, cheap enough to run on every frame. */
    private fun sampleLuminance(bitmap: Bitmap) {
        var index = 0
        for (row in 0 until SAMPLE_ROWS) {
            val y = bitmap.height * (row * 2 + 1) / (SAMPLE_ROWS * 2)
            for (col in 0 until SAMPLE_COLUMNS) {
                val x = bitmap.width * (col * 2 + 1) / (SAMPLE_COLUMNS * 2)
                val pixel = bitmap.getPixel(x, y)
                sample[index++] = (((pixel shr 16) and 0xff) * 77 +
                    ((pixel shr 8) and 0xff) * 151 +
                    (pixel and 0xff) * 28) shr 8
            }
        }
    }

    /**
     * Forgets detection history so a restarted stream is judged on fresh frames.
     * Starts pessimistic: a stale frame left in the card's buffer must not count
     * as a live picture when coming back from the review screen.
     */
    fun resetSignalTracking() {
        blankStreak = 0
        goodStreak = 0
        blankReported = true
    }

    private fun drawBlank() {
        val canvas = lockCanvasCompat() ?: return
        try {
            canvas.drawColor(Color.BLACK)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun lockCanvasCompat(): Canvas? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
    }.getOrNull()

    private fun fitCenter(srcW: Int, srcH: Int, dstW: Int, dstH: Int, out: Rect) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            out.set(0, 0, dstW, dstH)
            return
        }
        val scale = minOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val w = (srcW * scale).toInt()
        val h = (srcH * scale).toInt()
        val left = (dstW - w) / 2
        val top = (dstH - h) / 2
        out.set(left, top, left + w, top + h)
    }
}
