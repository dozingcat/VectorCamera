package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript

/**
 * Created by brian on 10/13/17.
 */
class AsciiEffect(val rs: RenderScript): Effect {
    var characterWidthInPixels = 15
    var charHeightOverWidth = 9.0 / 7
    var backgroundColor = Color.argb(255, 0, 0, 0)
    var textColor = Color.argb(255, 255, 255, 255)
    var pixelChars = " .:o08#"

    private var asciiBlockAllocation: Allocation? = null
    private var characterTemplateAllocation: Allocation? = null
    private var bitmapOutputAllocation: Allocation? = null
    private val script = ScriptC_ascii(rs)

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

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val width = cameraImage.width()
        val height = cameraImage.height()
        // TODO: Reuse resultBitmap and charBitmap if possible.
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val charPixelWidth = characterWidthInPixels
        val charPixelHeight = Math.floor(charPixelWidth * charHeightOverWidth).toInt()
        val numCharacterRows = height / charPixelHeight
        val numCharacterColumns = width / charPixelWidth

        // Create Bitmap and draw each character into it.
        val paint = Paint()
        paint.textSize = charPixelHeight.toFloat()
        paint.color = textColor

        val charBitmap = Bitmap.createBitmap(
                charPixelWidth * pixelChars.length, charPixelHeight, Bitmap.Config.ARGB_8888)
        val charBitmapCanvas = Canvas(charBitmap)
        charBitmapCanvas.drawColor(backgroundColor)

        for (i in 0 until pixelChars.length) {
            charBitmapCanvas.drawText(
                    pixelChars[i].toString(), (i * charPixelWidth).toFloat(), charPixelHeight - 1f, paint)
        }

        val result = computeAsciiResult(
                cameraImage, pixelChars,
                charPixelWidth, charPixelHeight, numCharacterColumns, numCharacterRows, charBitmap)

        bitmapOutputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    fun computeAsciiResult(camAllocation: CameraImage, pixelChars: String,
                           charPixelWidth: Int, charPixelHeight: Int,
                           numCharacterColumns: Int, numCharacterRows: Int,
                           charBitmap: Bitmap): AsciiResult {
        if (!allocationHas2DSize(asciiBlockAllocation, numCharacterColumns, numCharacterRows)) {
            asciiBlockAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    numCharacterColumns, numCharacterRows)
        }
        if (!allocationHas2DSize(
                bitmapOutputAllocation, camAllocation.width(), camAllocation.height())) {
            bitmapOutputAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    camAllocation.width(), camAllocation.height())
        }
        if (!allocationHas2DSize(characterTemplateAllocation,
                pixelChars.length * charPixelWidth, charPixelHeight)) {
            characterTemplateAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    pixelChars.length * charPixelWidth, charPixelHeight)
        }
        characterTemplateAllocation!!.copyFrom(charBitmap)

        script._characterBitmapInput = characterTemplateAllocation
        script._imageOutput = bitmapOutputAllocation
        script._imageWidth = camAllocation.width()
        script._imageHeight = camAllocation.height()
        script._numCharColumns = numCharacterColumns
        script._numCharRows = numCharacterRows
        script._numCharacters = pixelChars.length
        script._flipHorizontal = camAllocation.orientation.isXFlipped()
        script._flipVertical = camAllocation.orientation.isYFlipped()
        if (camAllocation.planarYuvAllocations != null) {
            script._yInput = camAllocation.planarYuvAllocations.y
            script._uInput = camAllocation.planarYuvAllocations.u
            script._vInput = camAllocation.planarYuvAllocations.v
            script.forEach_computeBlockAverages_planar(asciiBlockAllocation)
        }
        else {
            script._yuvInput = camAllocation.singleYuvAllocation
            script.forEach_computeBlockAverages(asciiBlockAllocation)
        }

        val allocBytes = ByteArray(4 * numCharacterColumns * numCharacterRows)
        asciiBlockAllocation!!.copyTo(allocBytes)

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