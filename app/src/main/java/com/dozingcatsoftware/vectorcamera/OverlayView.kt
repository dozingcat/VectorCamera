package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    var processedBitmap: ProcessedBitmap? = null
    var touchEventHandler: ((OverlayView, MotionEvent) -> Unit)? = null
    var generationTimeAverageNanos: Double = 0.0
    var showDebugInfo = true

    private val flipMatrix = Matrix()
    private val imageRect = RectF()
    private val blackPaint = Paint().apply {color = Color.BLACK}
    private val statsPaint = Paint().apply {color = Color.WHITE}

    fun updateBitmap(pb: ProcessedBitmap, showDebug: Boolean = false) {
        processedBitmap = pb
        showDebugInfo = showDebug
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pb = this.processedBitmap
        pb?.renderToCanvas(canvas, this.width, this.height, blackPaint, imageRect, flipMatrix)
        if (showDebugInfo && pb != null) {
            val density = resources.displayMetrics.density
            statsPaint.textSize = 20 * density
            val x = 16 * density
            val y = 16 * density
            val ms = (generationTimeAverageNanos / 1e6).roundToInt()
            val archStr = if (pb.metadata.codeArchitecture != null) pb.metadata.codeArchitecture.name else " "
            val threadStr = if (pb.metadata.numThreads != null) "${pb.metadata.numThreads}T" else " "
            val msg = "${pb.effect.effectName()} ${pb.bitmap.width}x${pb.bitmap.height} $archStr $threadStr ${ms}ms"
            canvas.drawText(msg, x, y, statsPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val handler = touchEventHandler
        if (handler != null) {
            handler(this, event!!)
            return true
        }
        return false
    }
}
