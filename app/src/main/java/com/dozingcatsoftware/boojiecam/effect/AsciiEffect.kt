package com.dozingcatsoftware.boojiecam.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.util.Size
import com.dozingcatsoftware.boojiecam.*

enum class AsciiColorMode(val id: Int) {
    FIXED(0),
    PRIMARY(1),
    FULL(2);

    companion object {
        fun fromString(s: String): AsciiColorMode {
            return AsciiColorMode.valueOf(s.toUpperCase())
        }
    }
}

class AsciiEffect(private val rs: RenderScript,
                  private val effectParams: Map<String, Any>,
                  private val textColor: Int,
                  private val backgroundColor: Int,
                  private val colorMode: AsciiColorMode): Effect {
    var characterWidthInPixels = 15
    var charHeightOverWidth = 9.0 / 7
    var pixelChars = " .:o08#"

    private var asciiBlockAllocation: Allocation? = null
    private var characterTemplateAllocation: Allocation? = null
    private var bitmapOutputAllocation: Allocation? = null
    private val script = ScriptC_ascii(rs)

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    private val backgroundPaint = run {
        val p = Paint()
        p.color = backgroundColor
        p
    }

    override fun createPaintFn(cameraImage: CameraImage) = {_: RectF -> backgroundPaint}

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
        val outputWidth = cameraImage.displaySize.width
        val outputHeight = cameraImage.displaySize.height
        // TODO: Reuse resultBitmap and charBitmap if possible.
        val resultBitmap = Bitmap.createBitmap(
                cameraImage.displaySize.width, cameraImage.displaySize.height,
                Bitmap.Config.ARGB_8888)
        val charPixelWidth = characterWidthInPixels
        val charPixelHeight = Math.floor(charPixelWidth * charHeightOverWidth).toInt()
        val numCharacterRows = outputHeight / charPixelHeight
        val numCharacterColumns = outputWidth / charPixelWidth

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
        val ds = camAllocation.displaySize
        if (!allocationHas2DSize(bitmapOutputAllocation, ds.width, ds.height)) {
            bitmapOutputAllocation = create2dAllocation(rs, Element::RGBA_8888, ds.width, ds.height)
        }
        if (!allocationHas2DSize(characterTemplateAllocation,
                pixelChars.length * charPixelWidth, charPixelHeight)) {
            characterTemplateAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    pixelChars.length * charPixelWidth, charPixelHeight)
        }
        characterTemplateAllocation!!.copyFrom(charBitmap)

        script._characterBitmapInput = characterTemplateAllocation
        script._imageOutput = bitmapOutputAllocation
        script._inputImageWidth = camAllocation.width()
        script._inputImageHeight = camAllocation.height()
        script._characterPixelWidth = charPixelWidth
        script._characterPixelHeight = charPixelHeight
        script._numCharColumns = numCharacterColumns
        script._numCharRows = numCharacterRows
        script._numCharacters = pixelChars.length
        script._flipHorizontal = camAllocation.orientation.isXFlipped()
        script._flipVertical = camAllocation.orientation.isYFlipped()
        script._colorMode = colorMode.id
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

    companion object {
        val EFFECT_NAME = "ascii"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): AsciiEffect {
            val colors = params.getOrElse("colors", {mapOf<String, Any>()}) as Map<String, Any>
            val textColor = intFromArgbList(
                    colors.getOrElse("text", {listOf(255, 255, 255)}) as List<Int>)
            val backgroundColor = intFromArgbList(
                    colors.getOrElse("background", {listOf(0, 0, 0)}) as List<Int>)
            val colorType = try {
                AsciiColorMode.fromString(params.getOrDefault("colorMode", "fixed") as String)
            } catch (ex: IllegalArgumentException) {AsciiColorMode.FIXED}

            return AsciiEffect(rs, params, textColor, backgroundColor, colorType)
        }
    }
}