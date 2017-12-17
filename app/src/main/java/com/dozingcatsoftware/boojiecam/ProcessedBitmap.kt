package com.dozingcatsoftware.boojiecam

import android.graphics.*
import com.dozingcatsoftware.boojiecam.effect.Effect

data class ProcessedBitmap(
        val effect: Effect,
        val sourceImage: CameraImage,
        val bitmap: Bitmap,
        val backgroundPaintFn: (RectF) -> Paint?) {

    fun renderToCanvas(canvas: Canvas, width: Int, height: Int, outsidePaint: Paint? = null,
                       tmpRect: RectF? = null, tmpMatrix: Matrix? = null) {
        val dstRect = tmpRect ?: RectF()
        val flipMatrix = tmpMatrix ?: Matrix()

        val scaleFactor = Math.min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val scaledWidth = bitmap.width * scaleFactor
        val scaledHeight = bitmap.height * scaleFactor

        val flipHorizontal = sourceImage.orientation.isXFlipped()
        val flipVertical = sourceImage.orientation.isYFlipped()
        var xOffset = (width - scaledWidth) / 2
        var yOffset = (height - scaledHeight) / 2

        flipMatrix.setScale(if (flipHorizontal) -scaleFactor else scaleFactor,
                if (flipVertical) -scaleFactor else scaleFactor)
        flipMatrix.postTranslate(if (flipHorizontal) xOffset + scaledWidth else xOffset,
                if (flipVertical) yOffset + scaledHeight else yOffset)

        if (xOffset > 0 && outsidePaint != null) {
            canvas.drawRect(0f, 0f, xOffset, height.toFloat(), outsidePaint)
            canvas.drawRect(width - xOffset, 0f, width.toFloat(), height.toFloat(), outsidePaint)
        }
        if (yOffset > 0 && outsidePaint != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), yOffset, outsidePaint)
            canvas.drawRect(0f, height - yOffset, width.toFloat(), height.toFloat(), outsidePaint)
        }
        dstRect.set(xOffset, yOffset, xOffset + scaledWidth, yOffset + scaledHeight)
        val paint = backgroundPaintFn(dstRect)
        if (paint != null) {
            canvas.drawRect(dstRect, paint)
        }
        canvas.drawBitmap(bitmap, flipMatrix, null)
    }

    fun renderBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        renderToCanvas(canvas, width, height)
        return bitmap
    }
}