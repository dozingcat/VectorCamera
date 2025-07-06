package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Script
import android.util.Size
import com.dozingcatsoftware.vectorcamera.*
import com.dozingcatsoftware.util.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale

enum class AsciiColorMode(val id: Int) {
    FIXED(0),
    PRIMARY(1),
    FULL(2);

    companion object {
        fun fromString(s: String): AsciiColorMode {
            return AsciiColorMode.valueOf(s.uppercase(Locale.getDefault()))
        }
    }
}

class AsciiEffect(private val rs: RenderScript,
                  private val effectParams: Map<String, Any>,
                  numPreferredCharColumns: Int,
                  private val textColor: Int,
                  private val backgroundColor: Int,
                  private val pixelChars: String,
                  private val colorMode: AsciiColorMode): Effect {

    private val textParams = TextParams(numPreferredCharColumns, 10, 1.8)

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

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        canvas.drawRect(rect, backgroundPaint)
    }

    class AsciiResult(val numRows: Int, val numCols: Int) {
        private val characters = CharArray(numRows * numCols)
        // Colors are stored as 24-bit packed rgb values. The upper 8 bits are not used.
        private val colors = IntArray(numRows * numCols)

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
        val metrics = textParams.getTextMetrics(cameraImage, cameraImage.displaySize)
        // TODO: Reuse resultBitmap and charBitmap if possible.
        val resultBitmap = Bitmap.createBitmap(
                metrics.outputSize.width, metrics.outputSize.height, Bitmap.Config.ARGB_8888)

        val cps = metrics.charPixelSize
        // Create Bitmap and draw each character into it.
        val paint = Paint()
        paint.textSize = cps.height * 5f / 6
        paint.color = textColor

        val charBitmap = Bitmap.createBitmap(
                cps.width * pixelChars.length, cps.height, Bitmap.Config.ARGB_8888)
        val charBitmapCanvas = Canvas(charBitmap)
        charBitmapCanvas.drawColor(backgroundColor)
        for (i in 0 until pixelChars.length) {
            charBitmapCanvas.drawText(pixelChars[i].toString(),
                    (i * cps.width).toFloat(), cps.height - 1f, paint)
        }
        characterTemplateAllocation = reuseOrCreate2dAllocation(characterTemplateAllocation,
                rs, Element::RGBA_8888,charBitmap.width, charBitmap.height)

        characterTemplateAllocation!!.copyFrom(charBitmap)

        bitmapOutputAllocation = reuseOrCreate2dAllocation(bitmapOutputAllocation,
                rs, Element::RGBA_8888, resultBitmap.width, resultBitmap.height)

        script._characterBitmapInput = characterTemplateAllocation
        script._imageOutput = bitmapOutputAllocation
        script._inputImageWidth = cameraImage.width()
        script._inputImageHeight = cameraImage.height()
        script._characterPixelWidth = cps.width
        script._characterPixelHeight = cps.height
        script._numCharColumns = metrics.numCharacterColumns
        script._numCharRows = metrics.numCharacterRows
        script._numCharacters = pixelChars.length
        script._flipHorizontal = cameraImage.orientation.xFlipped
        script._flipVertical = cameraImage.orientation.yFlipped
        script._portrait = metrics.isPortrait
        script._colorMode = colorMode.id
        // There's no input allocation passed directly to the kernel so we manually set x/y ranges.
        val options = Script.LaunchOptions()
                .setX(0, metrics.numCharacterColumns).setY(0, metrics.numCharacterRows)
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
            cameraImage: CameraImage, pixelChars: String, metrics: TextMetrics): AsciiResult {
        asciiBlockAllocation = reuseOrCreate2dAllocation(asciiBlockAllocation,
                rs, Element::RGBA_8888, metrics.numCharacterColumns, metrics.numCharacterRows)

        script._inputImageWidth = cameraImage.width()
        script._inputImageHeight = cameraImage.height()
        script._characterPixelWidth = metrics.charPixelSize.width
        script._characterPixelHeight = metrics.charPixelSize.height
        script._numCharColumns = metrics.numCharacterColumns
        script._numCharRows = metrics.numCharacterRows
        script._numCharacters = pixelChars.length
        script._flipHorizontal = cameraImage.orientation.xFlipped
        script._flipVertical = cameraImage.orientation.yFlipped
        script._portrait = cameraImage.orientation.portrait
        script._colorMode = colorMode.id
        if (cameraImage.planarYuvAllocations != null) {
            script._hasSingleYuvAllocation = false
            script._yInput = cameraImage.planarYuvAllocations.y
            script._uInput = cameraImage.planarYuvAllocations.u
            script._vInput = cameraImage.planarYuvAllocations.v
        }
        else {
            script._hasSingleYuvAllocation = true
            script._yuvInput = cameraImage.singleYuvAllocation
        }
        script.forEach_computeCharacterInfoForBlock(asciiBlockAllocation)

        val allocBytes = ByteArray(4 * metrics.numCharacterColumns * metrics.numCharacterRows)
        asciiBlockAllocation!!.copyTo(allocBytes)

        val result = AsciiResult(metrics.numCharacterRows, metrics.numCharacterColumns)
        // Average brightness is stored in the alpha component, and allocations are RGBA.
        var allocIndex = 0
        for (r in 0 until metrics.numCharacterRows) {
            for (c in 0 until metrics.numCharacterColumns) {
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
        val metrics = textParams.getTextMetrics(cameraImage, EFFECTIVE_SIZE_FOR_TEXT_OUTPUT)
        val asciiResult = getCharacterInfo(
                cameraImage, pixelChars, metrics)
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        for (r in 0 until metrics.numCharacterRows) {
            for (c in 0 until metrics.numCharacterColumns) {
                writer.write(asciiResult.charAtRowAndColumn(r, c).toString())
            }
            writer.write("\n")
        }
        writer.flush()
    }

    fun writeHtml(cameraImage: CameraImage, out: OutputStream) {
        val metrics = textParams.getTextMetrics(cameraImage, EFFECTIVE_SIZE_FOR_TEXT_OUTPUT)
        val asciiResult = getCharacterInfo(
                cameraImage, pixelChars, metrics)

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

        for (r in 0 until metrics.numCharacterRows) {
            var lastColor = 0
            writer.write("<pre>")
            for (c in 0 until metrics.numCharacterColumns) {
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
        const val DEFAULT_CHARACTER_COLUMNS = 120
        val EFFECTIVE_SIZE_FOR_TEXT_OUTPUT = Size(2560, 1600)

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): AsciiEffect {
            val colors = params.getOrElse("colors", {mapOf<String, Any>()}) as Map<String, Any>
            val textColor = intFromArgbList(
                    colors.getOrElse("text", {listOf(255, 255, 255)}) as List<Int>)
            val backgroundColor = intFromArgbList(
                    colors.getOrElse("background", {listOf(0, 0, 0)}) as List<Int>)
            val numCharColumns = params.getOrElse("numColumns", {DEFAULT_CHARACTER_COLUMNS}) as Int

            var pixelChars = params.getOrElse("pixelChars", {""}) as String
            if (pixelChars.isEmpty()) {
                // Shouldn't happen, set a reasonable default.
                pixelChars = " .o8"
            }

            val colorType = try {
                AsciiColorMode.fromString(params.getOrElse("colorMode", {"fixed"}) as String)
            } catch (ex: IllegalArgumentException) {AsciiColorMode.FIXED}

            return AsciiEffect(
                    rs, params, numCharColumns, textColor, backgroundColor, pixelChars, colorType)
        }

        private fun cssHexColor(color: Int): String {
            fun toHex2(x: Int): String {
                val s = Integer.toHexString(x)
                return (if (s.length > 1) s else "0$s")
            }
            val red = toHex2((color shr 16) and 0xff)
            val green = toHex2((color shr 8) and 0xff)
            val blue = toHex2(color  and 0xff)
            return "#${red}${green}${blue}"
        }
    }
}