package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Created by brian on 10/13/17.
 */
class AsciiImageProcessor {
    var numCharacterColumns = 80
    var charAspectRatio = 9.0 / 7
    var backgroundColor = Color.argb(255, 0, 0, 0)
    var textColor = Color.argb(255, 255, 255, 255)
    var pixelChars = " .:oO8#"

    // private var resultBitmap1: Bitmap? = null
    // private var resultBitmap2: Bitmap? = null
    // private var lastUsedBitmap: Bitmap? = null

    class AsciiResult(val numRows: Int, val numCols: Int) {
        val characters = CharArray(numRows * numCols)
        val colors = IntArray(numRows * numCols)

        fun setCharAtRowAndColumn(ch: Char, row: Int, col: Int) {
            characters[row * numCols + col] = ch
        }

        fun charAtRowAndColumn(row: Int, col: Int): Char {
            return characters[row * numCols + col]
        }
    }

    fun createBitmapFromImage(image: PlanarImage): Bitmap {
        val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val width = image.width
        val height = image.height
        val charPixelWidth = image.width / numCharacterColumns
        val charPixelHeight = Math.floor(charPixelWidth * charAspectRatio).toInt()
        val numCharacterRows = image.height / charPixelHeight

        val result = computeAsciiResult(
                image, pixelChars,
                charPixelWidth, charPixelHeight, numCharacterColumns, numCharacterRows)

        val canvas = Canvas(resultBitmap)
        canvas.drawColor(backgroundColor)
        val paint = Paint()
        paint.textSize = charPixelHeight.toFloat()
        paint.color = textColor
        for (row in 0 until result.numRows) {
            val y = (row * charPixelHeight).toFloat()
            for (col in 0 until result.numCols) {
                val x = (col * charPixelWidth).toFloat()
                canvas.drawText(result.charAtRowAndColumn(row, col).toString(), x, y, paint)
            }
        }

        return resultBitmap!!
    }

    fun computeAsciiResult(image: PlanarImage, pixelChars: String,
                           charPixelWidth: Int, charPixelHeight: Int,
                           numCharacterColumns: Int, numCharacterRows: Int): AsciiResult {
        val result = AsciiResult(numCharacterRows, numCharacterColumns)
        val brightness = getBufferBytes(image.planes[0].buffer)
        val rowStride = image.planes[0].rowStride
        for (r in 0 until numCharacterRows) {
            val y0 = r * charPixelHeight
            val y1 = y0 + charPixelHeight
            for (c in 0 until numCharacterColumns) {
                val x0 = c * charPixelWidth
                val x1 = x0 + charPixelWidth
                val avg = averageBrightness(brightness, image.width, rowStride, x0, y0, x1, y1)
                val index = Math.floor(avg / 256 * pixelChars.length).toInt()
                result.setCharAtRowAndColumn(pixelChars[index], r, c)
            }
        }
        return result
    }

    fun averageBrightness(brightness: ByteArray, width: Int, rowStride: Int,
                          x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var total = 0
        val npoints = (x1 - x0) * (y1 - y0)
        for (y in y0 until y1) {
            var offset = y * rowStride + x0
            for (x in x0 until x1) {
                total += toUInt(brightness[offset++])
            }
        }
        return total.toDouble() / npoints
    }
}