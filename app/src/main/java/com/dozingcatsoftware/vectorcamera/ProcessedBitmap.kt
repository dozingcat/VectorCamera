package com.dozingcatsoftware.vectorcamera

import android.graphics.*
import android.renderscript.RenderScript
import android.util.Size
import com.dozingcatsoftware.vectorcamera.effect.Effect

/**
 * The result of applying an Effect to a CameraImage. Contains references to the effect and
 * input image, and the bitmap created by applying the effect.
 */
data class ProcessedBitmap(
        val effect: Effect,
        val sourceImage: CameraImage,
        val bitmap: Bitmap,
        val yuvBytes: ByteArray? = null) {

    /**
     * Outputs the processed image to a canvas. Accepts optional Paint, RectF, and Matrix arguments
     * to avoid recreating temporary objects.
     */
    fun renderToCanvas(canvas: Canvas, width: Int, height: Int, outsidePaint: Paint? = null,
                       tmpRect: RectF? = null, tmpMatrix: Matrix? = null) {
        val dstRect = tmpRect ?: RectF()
        // val flipMatrix = tmpMatrix ?: Matrix()
        val flipMatrix = Matrix()
        val shouldRotate = (height > width)
        val bitmapWidth = if (shouldRotate) bitmap.height else bitmap.width
        val bitmapHeight = if (shouldRotate) bitmap.width else bitmap.height

        val scaleFactor = Math.min(width.toFloat() / bitmapWidth, height.toFloat() / bitmapHeight)
        val scaledWidth = bitmapWidth * scaleFactor
        val scaledHeight = bitmapHeight * scaleFactor

        val flipHorizontal = sourceImage.orientation.isXFlipped()
        val flipVertical = sourceImage.orientation.isYFlipped()
        var xOffset = (width - scaledWidth) / 2
        var yOffset = (height - scaledHeight) / 2

        // setScale overwrites postRotate?

        flipMatrix.postScale(if (flipHorizontal) -scaleFactor else scaleFactor,
                if (flipVertical) -scaleFactor else scaleFactor)
        flipMatrix.postTranslate(if (flipHorizontal) xOffset + scaledWidth else xOffset,
                if (flipVertical) yOffset + scaledHeight else yOffset)

        if (shouldRotate) {
            flipMatrix.postRotate(90f, bitmap.width / 2f, bitmap.height / 2f)
        }

        if (xOffset > 0 && outsidePaint != null) {
            canvas.drawRect(0f, 0f, xOffset, height.toFloat(), outsidePaint)
            canvas.drawRect(width - xOffset, 0f, width.toFloat(), height.toFloat(), outsidePaint)
        }
        if (yOffset > 0 && outsidePaint != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), yOffset, outsidePaint)
            canvas.drawRect(0f, height - yOffset, width.toFloat(), height.toFloat(), outsidePaint)
        }
        dstRect.set(xOffset, yOffset, xOffset + scaledWidth, yOffset + scaledHeight)

        effect.drawBackground(sourceImage, canvas, dstRect)
        canvas.drawBitmap(bitmap, flipMatrix, null)
    }

    fun renderBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        renderToCanvas(canvas, width, height)
        return bitmap
    }

    fun resizedTo(size: Size): ProcessedBitmap {
        val resizedSource = this.sourceImage.resizedTo(size)
        val bitmap = effect.createBitmap(resizedSource)
        return ProcessedBitmap(effect, resizedSource, bitmap)
    }
}
