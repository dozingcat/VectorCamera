package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import kotlin.math.roundToInt

// gridCornerColors is a 2d array where the values are lists of 12 RGB components
// in the order top-left, top-right, bottom-left, bottom-right. This creates a grid
// of gradient squares that a "window" slides over with speedX and speedY. Speed units are
// thousandths of a square per second.
class Animated2dGradient(val cellCornerColors: List<List<List<Int>>>,
                         val speedX: Int = 0, val speedY: Int = 0,
                         val sizeX: Float = 1f, val sizeY: Float = 1f,
                         val pixelsPerCell: Int = DEFAULT_PIXELS_PER_CELL) {

    private val gridBitmap: Bitmap
    private val numRowCells = cellCornerColors.size
    private val numColumnCells = cellCornerColors[0].size
    private val sizeXPixels = (sizeX * pixelsPerCell).roundToInt()
    private val sizeYPixels = (sizeY * pixelsPerCell).roundToInt()

    init {
        gridBitmap = Bitmap.createBitmap(
                numColumnCells * pixelsPerCell,
                numRowCells * pixelsPerCell,
                Bitmap.Config.ARGB_8888)

        val pixels = IntArray(gridBitmap.width * gridBitmap.height)
        var pixelIndex = 0
        for (cellY in 0 until numRowCells) {
            for (offsetY in 0 until pixelsPerCell) {
                val yFraction = offsetY.toDouble() / (pixelsPerCell - 1)
                for (cellX in 0 until numColumnCells) {
                    val cellColors = cellCornerColors[cellY][cellX]
                    val rLeft = cellColors[0] + yFraction * (cellColors[6] - cellColors[0])
                    var rRight = cellColors[3] + yFraction * (cellColors[9] - cellColors[3])
                    val rIncr = (rRight - rLeft) / (pixelsPerCell - 1)
                    val gLeft = cellColors[1] + yFraction * (cellColors[7] - cellColors[1])
                    var gRight = cellColors[4] + yFraction * (cellColors[10] - cellColors[4])
                    val gIncr = (gRight - gLeft) / (pixelsPerCell - 1)
                    val bLeft = cellColors[2] + yFraction * (cellColors[8] - cellColors[2])
                    var bRight = cellColors[5] + yFraction * (cellColors[11] - cellColors[5])
                    val bIncr = (bRight - bLeft) / (pixelsPerCell - 1)

                    var red = rLeft
                    var green = gLeft
                    var blue = bLeft
                    for (offsetX in 0 until pixelsPerCell) {
                        pixels[pixelIndex++] = Color.argb(255,
                                Math.round(red).toInt(),
                                Math.round(green).toInt(),
                                Math.round(blue).toInt())
                        red += rIncr
                        green += gIncr
                        blue += bIncr
                    }
                }
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
    }
}
