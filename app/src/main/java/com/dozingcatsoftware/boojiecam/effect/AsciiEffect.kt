package com.dozingcatsoftware.boojiecam.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Script
import android.util.Size
import com.dozingcatsoftware.boojiecam.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlin.math.roundToInt

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
                  private val pixelChars: String,
                  private val colorMode: AsciiColorMode): Effect {
    var characterWidthInPixels = 15
    var charHeightOverWidth = 1.8

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

    // HERE: Update to hold colors and add HTML/text output.
    class AsciiResult(val numRows: Int, val numCols: Int) {
        val characters = CharArray(numRows * numCols)
        // Colors are stored as 24-bit packed rgb values. The upper 8 bits are not used.
        val colors = IntArray(numRows * numCols)

        private fun getIndex(row: Int, col: Int) = row * numCols + col

        fun setCharAtRowAndColumn(ch: Char, red: Int, green: Int, blue: Int, row: Int, col: Int) {
            val index = getIndex(row, col)
            characters[index] = ch
            colors[index] = (red shl 16) or (green shl 8) or blue
        }

        fun colorAtRowAndColumn(row: Int, col: Int): Int {
            return colors[getIndex(row, col)]
        }

        fun charAtRowAndColumn(row: Int, col: Int): Char {
            return characters[getIndex(row, col)]
        }
    }

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val outputSize = computeOutputSize(cameraImage.size(), cameraImage.displaySize)
        val charPixelWidth = characterWidthInPixels
        val charPixelHeight = (charPixelWidth * charHeightOverWidth).roundToInt()
        val numCharacterRows = outputSize.height / charPixelHeight
        val numCharacterColumns = outputSize.width / charPixelWidth

        val outputWidth = numCharacterColumns * charPixelWidth
        val outputHeight = numCharacterRows * charPixelHeight
        // TODO: Reuse resultBitmap and charBitmap if possible.
        val resultBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)

        // Create Bitmap and draw each character into it.
        val paint = Paint()
        paint.textSize = charPixelHeight * 5f / 6
        paint.color = textColor

        val charBitmap = Bitmap.createBitmap(
                charPixelWidth * pixelChars.length, charPixelHeight, Bitmap.Config.ARGB_8888)
        val charBitmapCanvas = Canvas(charBitmap)
        charBitmapCanvas.drawColor(backgroundColor)
        for (i in 0 until pixelChars.length) {
            charBitmapCanvas.drawText(pixelChars[i].toString(),
                    (i * charPixelWidth).toFloat(), charPixelHeight - 1f, paint)
        }
        if (!allocationHas2DSize(characterTemplateAllocation,
                        pixelChars.length * charPixelWidth, charPixelHeight)) {
            characterTemplateAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    pixelChars.length * charPixelWidth, charPixelHeight)
        }
        characterTemplateAllocation!!.copyFrom(charBitmap)

        if (!allocationHas2DSize(bitmapOutputAllocation, outputWidth, outputHeight)) {
            bitmapOutputAllocation = create2dAllocation(
                    rs, Element::RGBA_8888, outputWidth, outputHeight)
        }

        script._characterBitmapInput = characterTemplateAllocation
        script._imageOutput = bitmapOutputAllocation
        script._inputImageWidth = cameraImage.width()
        script._inputImageHeight = cameraImage.height()
        script._characterPixelWidth = charPixelWidth
        script._characterPixelHeight = charPixelHeight
        script._numCharColumns = numCharacterColumns
        script._numCharRows = numCharacterRows
        script._numCharacters = pixelChars.length
        script._flipHorizontal = cameraImage.orientation.isXFlipped()
        script._flipVertical = cameraImage.orientation.isYFlipped()
        script._colorMode = colorMode.id
        // There's no input allocation passed directly to the kernel so we manually set x/y ranges.
        val options = Script.LaunchOptions().setX(0, numCharacterColumns).setY(0, numCharacterRows)
        if (cameraImage.planarYuvAllocations != null) {
            script._hasSingleYuvAllocation = false
            script._yInput = cameraImage.planarYuvAllocations.y
            script._uInput = cameraImage.planarYuvAllocations.u
            script._vInput = cameraImage.planarYuvAllocations.v
            script.forEach_writeCharacterToBitmap(options)
        }
        else {
            script._hasSingleYuvAllocation = true
            script._yuvInput = cameraImage.singleYuvAllocation
            script.forEach_writeCharacterToBitmap(options)
        }
        bitmapOutputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    private fun getCharacterInfo(
            camAllocation: CameraImage, pixelChars: String,
            charPixelWidth: Int, charPixelHeight: Int,
            numCharacterColumns: Int, numCharacterRows: Int): AsciiResult {
        if (!allocationHas2DSize(asciiBlockAllocation, numCharacterColumns, numCharacterRows)) {
            asciiBlockAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    numCharacterColumns, numCharacterRows)
        }
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
            script._hasSingleYuvAllocation = false
            script._yInput = camAllocation.planarYuvAllocations.y
            script._uInput = camAllocation.planarYuvAllocations.u
            script._vInput = camAllocation.planarYuvAllocations.v
            script.forEach_computeCharacterInfoForBlock(asciiBlockAllocation)
        }
        else {
            script._hasSingleYuvAllocation = true
            script._yuvInput = camAllocation.singleYuvAllocation
            script.forEach_computeCharacterInfoForBlock(asciiBlockAllocation)
        }

        val allocBytes = ByteArray(4 * numCharacterColumns * numCharacterRows)
        asciiBlockAllocation!!.copyTo(allocBytes)

        val result = AsciiResult(numCharacterRows, numCharacterColumns)
        // Average brightness is stored in the alpha component, and allocations are RGBA.
        var allocIndex = 0
        for (r in 0 until numCharacterRows) {
            for (c in 0 until numCharacterColumns) {
                val red = toUInt(allocBytes[allocIndex])
                val green = toUInt(allocBytes[allocIndex + 1])
                val blue = toUInt(allocBytes[allocIndex + 2])
                val brightness = toUInt(allocBytes[allocIndex + 3])
                allocIndex += 4
                val charIndex = Math.floor(brightness / 256.0 * pixelChars.length).toInt()
                result.setCharAtRowAndColumn(pixelChars[charIndex], red, green, blue, r, c)
            }
        }
        return result
    }

    fun writeText(cameraImage: CameraImage, out: OutputStream) {
        val outputSize = computeOutputSize(cameraImage.size(), EFFECTIVE_SIZE_FOR_TEXT_OUTPUT)
        val charPixelWidth = characterWidthInPixels
        val charPixelHeight = (charPixelWidth * charHeightOverWidth).roundToInt()
        val numCharacterRows = outputSize.height / charPixelHeight
        val numCharacterColumns = outputSize.width / charPixelWidth

        val asciiResult = getCharacterInfo(
                cameraImage, pixelChars, charPixelWidth, charPixelHeight,
                numCharacterColumns, numCharacterRows)
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        for (r in 0 until numCharacterRows) {
            for (c in 0 until numCharacterColumns) {
                writer.write(asciiResult.charAtRowAndColumn(r, c).toString())
            }
            writer.write("\n")
        }
        writer.flush()
    }

    fun writeHtml(cameraImage: CameraImage, out: OutputStream) {
        val outputSize = computeOutputSize(cameraImage.size(), EFFECTIVE_SIZE_FOR_TEXT_OUTPUT)
        val charPixelWidth = characterWidthInPixels
        val charPixelHeight = (charPixelWidth * charHeightOverWidth).roundToInt()
        val numCharacterRows = outputSize.height / charPixelHeight
        val numCharacterColumns = outputSize.width / charPixelWidth

        val asciiResult = getCharacterInfo(
                cameraImage, pixelChars, charPixelWidth, charPixelHeight,
                numCharacterColumns, numCharacterRows)

        val isFixedColor = (colorMode == AsciiColorMode.FIXED)
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        writer.write("<!DOCTYPE html>\n")
        writer.write("<html>\n<head>\n")
        writer.write("<meta charset='UTF-8'>\n")
        writer.write("<title>${Constants.APP_NAME} Picture</title>\n")

        writer.write("<style>\n")
        writer.write("body {background: ${cssHexColor(backgroundColor)};}\n")
        writer.write(".ascii i {font-style: normal;}\n")
        writer.write(".ascii pre {margin: 0;}\n")
        if (isFixedColor) {
            writer.write(".ascii {color: ${cssHexColor(textColor)};}\n")
        }
        writer.write("</style>\n")
        writer.write("</head>\n<body>\n<div class='ascii'>\n")

        for (r in 0 until numCharacterRows) {
            var lastColor = 0
            writer.write("<pre>")
            for (c in 0 until numCharacterColumns) {
                if (!isFixedColor) {
                    val charColor = asciiResult.colorAtRowAndColumn(r, c)
                    if (c == 0 || charColor != lastColor) {
                        if (c > 0) {
                            writer.write("</i>")
                        }
                        writer.write("<i style=color:${cssHexColor(charColor)}>")
                        lastColor = charColor
                    }
                }
                writer.write(asciiResult.charAtRowAndColumn(r, c).toString())
            }
            if (!isFixedColor) {
                writer.write("</i>")
            }
            writer.write("</pre>\n")
        }

        writer.write("</div>\n</body>\n</html>\n")
        writer.flush()
    }


    companion object {
        const val EFFECT_NAME = "ascii"
        val EFFECTIVE_SIZE_FOR_TEXT_OUTPUT = Size(2560, 1600)

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): AsciiEffect {
            val colors = params.getOrElse("colors", {mapOf<String, Any>()}) as Map<String, Any>
            val textColor = intFromArgbList(
                    colors.getOrElse("text", {listOf(255, 255, 255)}) as List<Int>)
            val backgroundColor = intFromArgbList(
                    colors.getOrElse("background", {listOf(0, 0, 0)}) as List<Int>)
            val pixelChars = params["pixelChars"] as String
            val colorType = try {
                AsciiColorMode.fromString(params.getOrDefault("colorMode", "fixed") as String)
            } catch (ex: IllegalArgumentException) {AsciiColorMode.FIXED}


            return AsciiEffect(rs, params, textColor, backgroundColor, pixelChars, colorType)
        }

        private fun computeOutputSize(inputSize: Size, targetOutputSize: Size): Size {
            val widthRatio = targetOutputSize.width.toDouble() / inputSize.width
            val heightRatio = targetOutputSize.height.toDouble() / inputSize.height
            val scale = Math.min(widthRatio, heightRatio)
            return Size(
                    (inputSize.width * scale).roundToInt(), (inputSize.height * scale).roundToInt())
        }

        private fun cssHexColor(color: Int): String {
            fun toHex2(x: Int): String {
                val s = Integer.toHexString(x)
                return (if (s.length > 1) s else "0" + s)
            }
            val red = toHex2((color shr 16) and 0xff)
            val green = toHex2((color shr 8) and 0xff)
            val blue = toHex2(color  and 0xff)
            return "#${red}${green}${blue}"
        }
    }
}