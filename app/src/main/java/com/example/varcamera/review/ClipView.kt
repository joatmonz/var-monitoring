package com.example.varcamera.review

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Surface the review player draws full-resolution clip frames onto.
 *
 * Zoom is applied here, while drawing, rather than by scaling the view: the frame
 * is drawn from the full 1080p bitmap, so zooming in reveals real detail instead
 * of enlarging an already-scaled picture.
 */
class ClipView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile private var scale = 1f
    @Volatile private var offsetX = 0f
    @Volatile private var offsetY = 0f
    @Volatile private var insetTop = 0f
    @Volatile private var insetBottom = 0f
    @Volatile private var redrawRequested = false
    @Volatile private var ready = false

    private val renderLock = Any()
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        holder.addCallback(this)
    }

    /** Same meaning as a graphicsLayer: scale about the centre, then translate. */
    fun setZoom(scale: Float, offsetX: Float, offsetY: Float) {
        if (scale == this.scale && offsetX == this.offsetX && offsetY == this.offsetY) return
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
        redrawRequested = true
    }

    /**
     * Keeps the picture clear of the controls: it is fitted between [top] and
     * [bottom] pixels from the edges instead of the whole surface. Changing the
     * fit area instead of the view size moves the picture without recreating the
     * surface, so it can be animated.
     */
    fun setInsets(top: Float, bottom: Float) {
        if (top == insetTop && bottom == insetBottom) return
        insetTop = top
        insetBottom = bottom
        redrawRequested = true
    }

    /** True once after the zoom or insets changed, or the surface was recreated. */
    fun consumeRedrawRequest(): Boolean {
        val requested = redrawRequested
        redrawRequested = false
        return requested
    }

    fun render(bitmap: Bitmap?) {
        synchronized(renderLock) {
            if (!ready) return
            val canvas = lock() ?: return
            try {
                canvas.drawColor(Color.BLACK)
                if (bitmap != null && !bitmap.isRecycled) {
                    computeDestination(bitmap.width, bitmap.height, canvas.width, canvas.height)
                    canvas.drawBitmap(bitmap, null, destination, paint)
                }
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ready = true
        redrawRequested = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        redrawRequested = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Waits out a draw in progress so the surface is never used after it is gone.
        synchronized(renderLock) { ready = false }
    }

    private fun lock(): Canvas? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
    }.getOrNull()

    private fun computeDestination(bitmapWidth: Int, bitmapHeight: Int, viewWidth: Int, viewHeight: Int) {
        val areaTop = insetTop
        val areaHeight = (viewHeight - insetTop - insetBottom).coerceAtLeast(1f)
        val fit = minOf(viewWidth.toFloat() / bitmapWidth, areaHeight / bitmapHeight)
        val width = bitmapWidth * fit
        val height = bitmapHeight * fit
        val centerX = viewWidth / 2f
        val centerY = areaTop + areaHeight / 2f
        val left = centerX + ((viewWidth - width) / 2f - centerX) * scale + offsetX
        val top = centerY + (areaTop + (areaHeight - height) / 2f - centerY) * scale + offsetY
        destination.set(left, top, left + width * scale, top + height * scale)
    }
}
