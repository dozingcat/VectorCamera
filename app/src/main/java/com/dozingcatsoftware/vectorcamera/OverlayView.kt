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

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    var processedBitmap: ProcessedBitmap? = null
    var touchEventHandler: ((OverlayView, MotionEvent) -> Unit)? = null

    private val flipMatrix = Matrix()
    private val imageRect = RectF()
    private val blackPaint = Paint().apply {color = Color.BLACK}

    override fun onDraw(canvas: Canvas) {
        val pb = this.processedBitmap
        pb?.renderToCanvas(canvas, this.width, this.height, blackPaint, imageRect, flipMatrix)
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
