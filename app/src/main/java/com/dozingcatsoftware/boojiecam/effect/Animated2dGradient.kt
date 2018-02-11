package com.dozingcatsoftware.boojiecam.effect

import android.graphics.*
import android.util.Log
import kotlin.math.roundToInt

// gridCornerColors is a 2d array where the values are lists of 12 RGB components
// in the order top-left, top-right, bottom-left, bottom-right. This creates a grid
// of gradient squares that a "window" slides over with speedX and speedY. Speed units are
// thousandths of a square per second.
class Animated2dGradient(val cellCornerColors: List<List<List<Int>>>,
                         val speedX: Int = 0, val speedY: Int = 0,
                         val sizeX: Float = 1f, val sizeY: Float = 1f,
                         val pixelsPerCell: Int = DEFAULT_PIXELS_PER_CELL) {

    val gridBitmap: Bitmap
    val numRowCells: Int
    val numColumnCells: Int
    val sizeXPixels = (sizeX * pixelsPerCell).roundToInt()
    val sizeYPixels = (sizeY * pixelsPerCell).roundToInt()

    init {
        numRowCells = cellCornerColors.size
        numColumnCells = cellCornerColors[0].size
        gridBitmap = Bitmap.createBitmap(
                numColumnCells * pixelsPerCell,
                numRowCells * pixelsPerCell,
                Bitmap.Config.ARGB_8888)

        val pixels = IntArray(gridBitmap.width * gridBitmap.height)
        var pixelIndex = 0
        for (y in 0 until gridBitmap.height) {
            val cellY = y / pixelsPerCell
            val offsetY = y % pixelsPerCell
            for (x in 0 until gridBitmap.width) {
                val cellX = x / pixelsPerCell
                val offsetX = x % pixelsPerCell
                val cell = cellCornerColors[cellY][cellX]
                pixels[pixelIndex++] = colorForCellOffset(cell, pixelsPerCell, offsetX, offsetY)
            }
        }
        gridBitmap.setPixels(pixels, 0, gridBitmap.width, 0, 0, gridBitmap.width, gridBitmap.height)
    }

    fun drawToCanvas(canvas: Canvas, dstRect: RectF, timestamp: Long) {
        val tbig = timestamp.toBigInteger()
        val windowX = tbig * speedX.toBigInteger() / 1000.toBigInteger()
        val windowY = tbig * speedY.toBigInteger() / 1000.toBigInteger()
        // windowX and windowY are in thousandths of grid cells. Normalize to [0, num_cells).
        val normX = (windowX % (numColumnCells * 1000).toBigInteger()).toDouble() / 1000
        val normY = (windowY % (numRowCells * 1000).toBigInteger()).toDouble() / 1000
        Log.i(TAG, "gradient coords: ${timestamp} ${windowX} ${windowY} ${normX} ${normY}")
        // Convert to bitmap pixels and see if we have to wrap around.
        val bitmapX = (normX * gridBitmap.width).roundToInt() % gridBitmap.width
        val bitmapY = (normY * gridBitmap.height).roundToInt() % gridBitmap.height
        val wrapX = (bitmapX + sizeXPixels > gridBitmap.width)
        val wrapY = (bitmapY + sizeYPixels > gridBitmap.height)
        // We may have to split the drawing for X, Y, or both.
        if (!wrapX && !wrapY) {
            canvas.drawBitmap(this.gridBitmap,
                    Rect(bitmapX, bitmapY, bitmapX + sizeXPixels, bitmapY + sizeYPixels),
                    dstRect, null)
        }
        else if (wrapX && !wrapY) {
            val spillX = bitmapX + sizeXPixels - gridBitmap.width
            val xSplitFraction = 1 - spillX.toFloat() / sizeXPixels
            val dstXSplit = dstRect.left + xSplitFraction * dstRect.width()
            canvas.drawBitmap(gridBitmap,
                    Rect(bitmapX, bitmapY, gridBitmap.width, bitmapY + sizeYPixels),
                    RectF(dstRect.left, dstRect.top, dstXSplit, dstRect.bottom),
                    null)
            canvas.drawBitmap(gridBitmap,
                    Rect(0, bitmapY, spillX, bitmapY + sizeYPixels),
                    RectF(dstXSplit, dstRect.top, dstRect.right, dstRect.bottom),
                    null)
        }
        else if (!wrapX && wrapY) {
            val spillY = bitmapY + sizeYPixels - gridBitmap.height
            val ySplitFraction = 1 - spillY.toFloat() / sizeYPixels
            val dstYSplit = dstRect.top + ySplitFraction * dstRect.height()
            canvas.drawBitmap(gridBitmap,
                    Rect(bitmapX, bitmapY, bitmapX + sizeXPixels, gridBitmap.height),
                    RectF(dstRect.left, dstRect.top, dstRect.right, dstYSplit),
                    null)
            canvas.drawBitmap(gridBitmap,
                    Rect(bitmapX, 0, bitmapX + sizeXPixels, spillY),
                    RectF(dstRect.left, dstYSplit, dstRect.right, dstRect.bottom),
                    null)
        }
        else {
            val spillX = bitmapX + sizeXPixels - gridBitmap.width
            val xSplitFraction = 1 - spillX.toFloat() / sizeXPixels
            val dstXSplit = dstRect.left + xSplitFraction * dstRect.width()
            val spillY = bitmapY + sizeYPixels - gridBitmap.height
            val ySplitFraction = 1 - spillY.toFloat() / sizeYPixels
            val dstYSplit = dstRect.top + ySplitFraction * dstRect.height()
            canvas.drawBitmap(gridBitmap,
                    Rect(bitmapX, bitmapY, gridBitmap.width, gridBitmap.height),
                    RectF(dstRect.left, dstRect.top, dstXSplit, dstYSplit),
                    null)
            canvas.drawBitmap(gridBitmap,
                    Rect(0, bitmapY, spillX, gridBitmap.height),
                    RectF(dstXSplit, dstRect.top, dstRect.right, dstYSplit),
                    null)
            canvas.drawBitmap(gridBitmap,
                    Rect(bitmapX, 0, gridBitmap.width, spillY),
                    RectF(dstRect.left, dstYSplit, dstXSplit, dstRect.bottom),
                    null)
            canvas.drawBitmap(gridBitmap,
                    Rect(0, 0, spillX, spillY),
                    RectF(dstXSplit, dstYSplit, dstRect.right, dstRect.bottom),
                    null)
        }

    }

    companion object {
        const val TAG = "Animated2dGradient"
        const val DEFAULT_PIXELS_PER_CELL = 80

        fun colorForCellOffset(
                colors: List<Int>, pixelsPerCell: Int, offsetX: Int, offsetY: Int): Int {

            fun colorComponentValue(
                    topLeft: Int, topRight: Int, bottomLeft: Int, bottomRight: Int): Int {
                val xFraction = offsetX.toDouble() / pixelsPerCell
                val yFraction = offsetY.toDouble() / pixelsPerCell
                val top = topLeft + (topRight - topLeft) * xFraction
                val bottom = bottomLeft + (bottomRight - bottomLeft) * xFraction
                return (top + (bottom - top) * yFraction).roundToInt()
            }

            val red = colorComponentValue(colors[0], colors[3], colors[6], colors[9])
            val green = colorComponentValue(colors[1], colors[4], colors[7], colors[10])
            val blue = colorComponentValue(colors[2], colors[5], colors[8], colors[11])
            return Color.argb(255, red, green, blue)
        }
    }
}