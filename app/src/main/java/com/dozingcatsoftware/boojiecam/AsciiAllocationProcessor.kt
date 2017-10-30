package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type
import android.util.Log

/**
 * Created by brian on 10/13/17.
 */
class AsciiAllocationProcessor(rs: RenderScript): CameraAllocationProcessor(rs) {
    var numCharacterColumns = 80
    var charAspectRatio = 9.0 / 7
    var backgroundColor = Color.argb(255, 0, 0, 0)
    var textColor = Color.argb(255, 255, 255, 255)
    var pixelChars = " .:oO8#"

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_ascii? = null

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

    override fun createBitmap(camAllocation: CameraImage): Bitmap {
        val width = camAllocation.width()
        val height = camAllocation.height()
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val charPixelWidth = width / numCharacterColumns
        val charPixelHeight = Math.floor(charPixelWidth * charAspectRatio).toInt()
        val numCharacterRows = height / charPixelHeight

        val result = computeAsciiResult(
                camAllocation, pixelChars,
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

        return resultBitmap
    }

    fun computeAsciiResult(camAllocation: CameraImage, pixelChars: String,
                           charPixelWidth: Int, charPixelHeight: Int,
                           numCharacterColumns: Int, numCharacterRows: Int): AsciiResult {
        if (script == null) {
            script = ScriptC_ascii(rs)
        }
        if (outputAllocation == null ||
                outputAllocation!!.type.x != numCharacterColumns ||
                outputAllocation!!.type.y != numCharacterRows) {
            val outputTypeBuilder = Type.Builder(rs, Element.RGBA_8888(rs))
            outputTypeBuilder.setX(numCharacterColumns)
            outputTypeBuilder.setY(numCharacterRows)
            outputAllocation = Allocation.createTyped(rs, outputTypeBuilder.create(),
                    Allocation.USAGE_SCRIPT)
        }

        script!!._yuvInput = camAllocation.allocation
        script!!._imageWidth = camAllocation.width()
        script!!._imageHeight = camAllocation.height()
        script!!._numCharColumns = numCharacterColumns
        script!!._numCharRows = numCharacterRows

        script!!.forEach_computeBlockAverages(outputAllocation)

        val allocBytes = ByteArray(4 * numCharacterColumns * numCharacterRows)
        outputAllocation!!.copyTo(allocBytes)

        val result = AsciiResult(numCharacterRows, numCharacterColumns)
        // Average brightness is stored in the alpha component, and allocations are RGBA.
        var allocIndex = 3
        for (r in 0 until numCharacterRows) {
            for (c in 0 until numCharacterColumns) {
                val brightnessAverage = toUInt(allocBytes[allocIndex])
                allocIndex += 4
                val charIndex = Math.floor(brightnessAverage / 256.0 * pixelChars.length).toInt()
                result.setCharAtRowAndColumn(pixelChars[charIndex], r, c)
            }
        }
        return result
    }
}