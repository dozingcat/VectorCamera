package com.dozingcatsoftware.boojiecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    var processedBitmap: ProcessedBitmap? = null

    private val flipMatrix = Matrix()
    private val blackPaint = Paint()
    private val imageRect = RectF()

    init {
        blackPaint.setARGB(255, 0, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        val pb = this.processedBitmap ?: return
        val bitmap = pb.bitmap
        val scaleFactor = Math.min(this.width.toFloat() / bitmap.width,
                this.height.toFloat() / bitmap.height)
        val scaledWidth = bitmap.width * scaleFactor
        val scaledHeight = bitmap.height * scaleFactor

        val flipHorizontal = (pb.sourceImage.orientation == ImageOrientation.ROTATED_180)
        val flipVertical = (pb.sourceImage.orientation == ImageOrientation.ROTATED_180)
        var xOffset = (width - scaledWidth) / 2
        var yOffset = (height - scaledHeight) / 2

        flipMatrix.setScale(if (flipHorizontal) -scaleFactor else scaleFactor,
                            if (flipVertical) -scaleFactor else scaleFactor)
        flipMatrix.postTranslate(if (flipHorizontal) xOffset + scaledWidth else xOffset,
                                 if (flipVertical) yOffset + scaledHeight else yOffset)

        if (xOffset > 0) {
            canvas.drawRect(0f, 0f, xOffset, height.toFloat(), blackPaint)
            canvas.drawRect(width - xOffset, 0f, width.toFloat(), height.toFloat(), blackPaint)
        }
        if (yOffset > 0) {
            canvas.drawRect(0f, 0f, width.toFloat(), yOffset, blackPaint)
            canvas.drawRect(0f, height - yOffset, width.toFloat(), height.toFloat(), blackPaint)
        }
        imageRect.set(xOffset, yOffset, xOffset + scaledWidth, yOffset + scaledHeight)
        val paint = pb.backgroundPaintFn(imageRect)
        if (paint != null) {
            canvas.drawRect(imageRect, paint)
        }
        canvas.drawBitmap(bitmap, flipMatrix, null)
    }

    companion object {
        val TAG = "OverlayView"
    }
}
