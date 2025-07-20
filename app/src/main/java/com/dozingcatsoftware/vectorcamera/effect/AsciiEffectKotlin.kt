package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.util.Size
import com.dozingcatsoftware.util.scaleToTargetSize
import com.dozingcatsoftware.util.intFromArgbList
import com.dozingcatsoftware.vectorcamera.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.*

/**
 * ASCII color modes for character rendering.
 */
enum class AsciiColorModeKotlin(val id: Int) {
    FIXED(0),    // Single text color
    PRIMARY(1),  // Primary color extraction 
    FULL(2);     // Full color from camera

    companion object {
        fun fromString(s: String): AsciiColorModeKotlin {
            return AsciiColorModeKotlin.valueOf(s.uppercase(Locale.getDefault()))
        }
    }
}

/**
 * Pure Kotlin implementation of AsciiEffect that converts camera images to ASCII art
 * using configurable character sets and color modes.
 */
class AsciiEffectKotlin(
    private val effectParams: Map<String, Any> = mapOf(),
    private val numPreferredCharColumns: Int = DEFAULT_CHARACTER_COLUMNS,
    private val textColor: Int = Color.WHITE,
    private val backgroundColor: Int = Color.BLACK,
    private val pixelChars: String = " .:oO8#",
    private val colorMode: AsciiColorModeKotlin = AsciiColorModeKotlin.FIXED
) : Effect {

    // Character grid layout
    private val textParams = TextParamsKotlin(numPreferredCharColumns, 10, 1.8)
    
    // Character template caching
    private var characterTemplate: Bitmap? = null
    private var lastCharPixelSize: Size? = null
    private var lastPixelChars: String? = null

    // Background paint for drawBackground
    private val backgroundPaint = Paint().apply { color = backgroundColor }

    override fun effectName() = EFFECT_NAME
    override fun effectParameters() = effectParams

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        canvas.drawRect(rect, backgroundPaint)
    }

    var numFrames: Int = 0

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val t1 = System.currentTimeMillis()
        
        val metrics = textParams.getTextMetrics(cameraImage, cameraImage.displaySize)
        
        // Compute brightness averages for each character cell
        val blockAverages = computeBlockAverages(cameraImage, metrics)
        
        // Map brightness to character indices and colors
        val (characterIndices, characterColors) = computeCharacterData(blockAverages, metrics, cameraImage)
        
        // Create character template bitmap if needed
        updateCharTemplateBitmap(metrics.charPixelSize)
        
        // Render final bitmap using bulk character rendering
        val resultBitmap = renderFinalBitmap(cameraImage, metrics, characterIndices, characterColors)
        
        val elapsed = System.currentTimeMillis() - t1
        if (++numFrames % 30 == 0) {
            Log.i(EFFECT_NAME, "Generated ${metrics.outputSize.width}x${metrics.outputSize.height} ASCII image in $elapsed ms")
        }
        
        return resultBitmap
    }

    /**
     * Compute average brightness for each character cell.
     */
    private fun computeBlockAverages(cameraImage: CameraImage, metrics: TextMetricsKotlin): ByteArray {
        val width = cameraImage.width()
        val height = cameraImage.height()
        val yuvBytes = cameraImage.getYuvBytes()!!
        val ySize = width * height
        val yData = yuvBytes.sliceArray(0 until ySize)
        
        val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
        val blockAverages = ByteArray(numCells)
        
        // Compute brightness for each character cell
        for (blockY in 0 until metrics.numCharacterRows) {
            for (blockX in 0 until metrics.numCharacterColumns) {
                val blockIndex = blockY * metrics.numCharacterColumns + blockX
                
                // Calculate input pixel region for this character cell
                val xmin: Int
                val xmax: Int
                val ymin: Int
                val ymax: Int
                
                if (metrics.isPortrait) {
                    val inputPixelsPerCol = height / metrics.numCharacterColumns
                    val inputPixelsPerRow = width / metrics.numCharacterRows
                    xmin = blockY * inputPixelsPerRow
                    xmax = xmin + inputPixelsPerRow
                    ymin = (metrics.numCharacterColumns - 1 - blockX) * inputPixelsPerCol
                    ymax = ymin + inputPixelsPerCol
                } else {
                    val inputPixelsPerCol = width / metrics.numCharacterColumns
                    val inputPixelsPerRow = height / metrics.numCharacterRows
                    xmin = blockX * inputPixelsPerCol
                    xmax = xmin + inputPixelsPerCol
                    ymin = blockY * inputPixelsPerRow
                    ymax = ymin + inputPixelsPerRow
                }
                
                // Average Y (brightness) values over the block
                var brightnessTotal = 0L
                var pixelCount = 0
                for (yy in ymin until ymax) {
                    for (xx in xmin until xmax) {
                        if (xx in 0 until width && yy in 0 until height) {
                            brightnessTotal += (yData[yy * width + xx].toInt() and 0xFF)
                            pixelCount++
                        }
                    }
                }
                
                blockAverages[blockIndex] = if (pixelCount > 0) 
                    (brightnessTotal / pixelCount).toByte() 
                else 
                    0.toByte()
            }
        }
        
        return blockAverages
    }

    /**
     * Map brightness values to character indices and colors based on color mode.
     */
    private fun computeCharacterData(
        blockAverages: ByteArray, 
        metrics: TextMetricsKotlin,
        cameraImage: CameraImage
    ): Pair<IntArray, IntArray> {
        val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
        val characterIndices = IntArray(numCells)
        val characterColors = IntArray(numCells)
        
        // For color modes that need camera color data
        val needsColorData = colorMode == AsciiColorModeKotlin.PRIMARY || colorMode == AsciiColorModeKotlin.FULL
        val colorData = if (needsColorData) computeBlockColors(cameraImage, metrics) else null
        
        for (i in 0 until numCells) {
            val brightness = blockAverages[i].toInt() and 0xFF
            
            // Map brightness to character index (darker = earlier in string, brighter = later)
            val charIndex = ((brightness / 256.0) * pixelChars.length).toInt().coerceIn(0, pixelChars.length - 1)
            characterIndices[i] = charIndex
            
            // Determine color based on color mode
            characterColors[i] = when (colorMode) {
                AsciiColorModeKotlin.FIXED -> textColor
                AsciiColorModeKotlin.PRIMARY -> {
                    val avgColor = colorData!![i]
                    computePrimaryColor(avgColor)
                }
                AsciiColorModeKotlin.FULL -> colorData!![i]
            }
        }
        
        return Pair(characterIndices, characterColors)
    }

    /**
     * Compute average colors for each character cell (for PRIMARY and FULL color modes).
     */
    private fun computeBlockColors(cameraImage: CameraImage, metrics: TextMetricsKotlin): IntArray {
        val width = cameraImage.width()
        val height = cameraImage.height()
        val yuvBytes = cameraImage.getYuvBytes()!!
        val ySize = width * height
        val yData = yuvBytes.sliceArray(0 until ySize)
        val uData = yuvBytes.sliceArray(ySize until ySize + ySize / 4)
        val vData = yuvBytes.sliceArray(ySize + ySize / 4 until yuvBytes.size)
        
        val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
        val blockColors = IntArray(numCells)
        
        for (blockY in 0 until metrics.numCharacterRows) {
            for (blockX in 0 until metrics.numCharacterColumns) {
                val blockIndex = blockY * metrics.numCharacterColumns + blockX
                
                // Calculate input pixel region
                val xmin: Int
                val xmax: Int  
                val ymin: Int
                val ymax: Int
                
                if (metrics.isPortrait) {
                    val inputPixelsPerCol = height / metrics.numCharacterColumns
                    val inputPixelsPerRow = width / metrics.numCharacterRows
                    xmin = blockY * inputPixelsPerRow
                    xmax = xmin + inputPixelsPerRow
                    ymin = (metrics.numCharacterColumns - 1 - blockX) * inputPixelsPerCol
                    ymax = ymin + inputPixelsPerCol
                } else {
                    val inputPixelsPerCol = width / metrics.numCharacterColumns
                    val inputPixelsPerRow = height / metrics.numCharacterRows
                    xmin = blockX * inputPixelsPerCol
                    xmax = xmin + inputPixelsPerCol
                    ymin = blockY * inputPixelsPerRow
                    ymax = ymin + inputPixelsPerRow
                }
                
                // Average RGB values over the block using YUV to RGB conversion
                var redTotal = 0L
                var greenTotal = 0L
                var blueTotal = 0L
                var pixelCount = 0
                
                for (yy in ymin until ymax) {
                    for (xx in xmin until xmax) {
                        if (xx in 0 until width && yy in 0 until height) {
                            val y = (yData[yy * width + xx].toInt() and 0xFF)
                            val u = (uData[(yy / 2) * (width / 2) + (xx / 2)].toInt() and 0xFF) - 128
                            val v = (vData[(yy / 2) * (width / 2) + (xx / 2)].toInt() and 0xFF) - 128
                            
                            // YUV to RGB conversion (ITU-R BT.601)
                            val r = (y + 1.402 * v).roundToInt().coerceIn(0, 255)
                            val g = (y - 0.344136 * u - 0.714136 * v).roundToInt().coerceIn(0, 255)
                            val b = (y + 1.772 * u).roundToInt().coerceIn(0, 255)
                            
                            redTotal += r
                            greenTotal += g
                            blueTotal += b
                            pixelCount++
                        }
                    }
                }
                
                if (pixelCount > 0) {
                    val avgRed = (redTotal / pixelCount).toInt()
                    val avgGreen = (greenTotal / pixelCount).toInt()
                    val avgBlue = (blueTotal / pixelCount).toInt()
                    blockColors[blockIndex] = Color.argb(255, avgRed, avgGreen, avgBlue)
                } else {
                    blockColors[blockIndex] = Color.BLACK
                }
            }
        }
        
        return blockColors
    }

    /**
     * Compute primary color by thresholding RGB components.
     */
    private fun computePrimaryColor(avgColor: Int): Int {
        val red = Color.red(avgColor)
        val green = Color.green(avgColor)
        val blue = Color.blue(avgColor)
        
        val maxComponent = maxOf(red, green, blue)
        val threshold = (maxComponent * 0.875).toInt() // PRIMARY_COLOR_RATIO from RenderScript
        
        val primaryRed = if (red >= threshold) 255 else 0
        val primaryGreen = if (green >= threshold) 255 else 0
        val primaryBlue = if (blue >= threshold) 255 else 0
        
        return Color.argb(255, primaryRed, primaryGreen, primaryBlue)
    }

    /**
     * Create or update the character template bitmap with ASCII characters.
     */
    private fun updateCharTemplateBitmap(charPixelSize: Size) {
        // Only recreate if size or characters changed
        if (characterTemplate != null && 
            charPixelSize == lastCharPixelSize && 
            pixelChars == lastPixelChars) {
            return
        }
        
        lastCharPixelSize = charPixelSize
        lastPixelChars = pixelChars
        val charBitmapWidth = charPixelSize.width * pixelChars.length
        
        val paint = Paint().apply {
            textSize = charPixelSize.height * 5f / 6
            color = textColor
            isAntiAlias = false
        }
        
        characterTemplate = Bitmap.createBitmap(charBitmapWidth, charPixelSize.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(characterTemplate!!)
        canvas.drawColor(backgroundColor)
        
        // Draw ASCII characters
        for (i in pixelChars.indices) {
            canvas.drawText(
                pixelChars[i].toString(),
                (i * charPixelSize.width).toFloat(),
                charPixelSize.height - 1f,
                paint
            )
        }
    }

    /**
     * Render the final bitmap by drawing ASCII characters into a grid.
     */
    private fun renderFinalBitmap(
        cameraImage: CameraImage, 
        metrics: TextMetricsKotlin,
        characterIndices: IntArray,
        characterColors: IntArray
    ): Bitmap {
        val resultBitmap = Bitmap.createBitmap(
            metrics.outputSize.width, 
            metrics.outputSize.height, 
            Bitmap.Config.ARGB_8888
        )
        
        val template = characterTemplate!!
        val charWidth = metrics.charPixelSize.width
        val charHeight = metrics.charPixelSize.height
        val outputPixels = IntArray(metrics.outputSize.width * metrics.outputSize.height)
        
        // Render character grid directly to pixel buffer (similar to MatrixEffect approach)
        renderCharacterGridKotlin(
            cameraImage, metrics, template, charWidth, charHeight, 
            characterIndices, characterColors, outputPixels
        )
        
        // Create bitmap from rendered pixels
        resultBitmap.setPixels(outputPixels, 0, metrics.outputSize.width, 0, 0, 
                              metrics.outputSize.width, metrics.outputSize.height)
        return resultBitmap
    }
    
    /**
     * Render character grid directly to pixel buffer for performance.
     */
    private fun renderCharacterGridKotlin(
        cameraImage: CameraImage, 
        metrics: TextMetricsKotlin, 
        template: Bitmap, 
        charWidth: Int, 
        charHeight: Int,
        characterIndices: IntArray,
        characterColors: IntArray,
        outputPixels: IntArray
    ) {
        // Fill with background color
        outputPixels.fill(backgroundColor)
        
        val templatePixels = IntArray(template.width * template.height)
        template.getPixels(templatePixels, 0, template.width, 0, 0, template.width, template.height)
        
        for (blockY in 0 until metrics.numCharacterRows) {
            for (blockX in 0 until metrics.numCharacterColumns) {
                val cellIndex = blockY * metrics.numCharacterColumns + blockX
                if (cellIndex >= characterIndices.size || cellIndex >= characterColors.size) continue
                
                val charIndex = characterIndices[cellIndex]
                val color = characterColors[cellIndex]
                
                // Calculate source and destination rectangles
                val srcLeft = charIndex * charWidth
                
                val dstLeft: Int
                val dstTop: Int
                val dstWidth: Int
                val dstHeight: Int
                
                if (metrics.isPortrait) {
                    dstLeft = blockY * charHeight
                    dstTop = (metrics.numCharacterColumns - 1 - blockX) * charWidth
                    dstWidth = charHeight
                    dstHeight = charWidth
                } else {
                    dstLeft = blockX * charWidth
                    dstTop = blockY * charHeight
                    dstWidth = charWidth
                    dstHeight = charHeight
                }
                
                // Render character pixels directly to output buffer
                for (dy in 0 until dstHeight) {
                    for (dx in 0 until dstWidth) {
                        var srcX: Int
                        var srcY: Int
                        
                        if (metrics.isPortrait) {
                            // Portrait transformation
                            srcX = srcLeft + (if (cameraImage.orientation.yFlipped) dy else charWidth - 1 - dy)
                            srcY = if (cameraImage.orientation.xFlipped) charHeight - 1 - dx else dx
                        } else {
                            // Landscape transformation
                            srcX = srcLeft + (if (cameraImage.orientation.xFlipped) charWidth - 1 - dx else dx)
                            srcY = if (cameraImage.orientation.yFlipped) charHeight - 1 - dy else dy
                        }
                        
                        // Bounds check
                        if (srcX >= srcLeft && srcX < srcLeft + charWidth && srcY >= 0 && srcY < charHeight) {
                            val templatePixel = templatePixels[srcY * template.width + srcX]
                            val templateRed = Color.red(templatePixel)
                            val templateGreen = Color.green(templatePixel)
                            val templateBlue = Color.blue(templatePixel)
                            
                            // For fixed color mode, replace non-background pixels with text color
                            // For other modes, replace non-background pixels with computed color
                            val outputColor = if (templateRed != Color.red(backgroundColor) ||
                                                 templateGreen != Color.green(backgroundColor) ||
                                                 templateBlue != Color.blue(backgroundColor)) {
                                color
                            } else {
                                backgroundColor
                            }
                            
                            val outX = dstLeft + dx
                            val outY = dstTop + dy
                            if (outX >= 0 && outX < metrics.outputSize.width && outY >= 0 && outY < metrics.outputSize.height) {
                                outputPixels[outY * metrics.outputSize.width + outX] = outputColor
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Result class for storing ASCII character and color data.
     */
    class AsciiResultKotlin(val numRows: Int, val numCols: Int) {
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

    /**
     * Generate character data for text/HTML export without rendering to bitmap.
     */
    private fun getCharacterInfo(
        cameraImage: CameraImage, 
        pixelChars: String, 
        metrics: TextMetricsKotlin
    ): AsciiResultKotlin {
        // Compute brightness averages for each character cell
        val blockAverages = computeBlockAverages(cameraImage, metrics)
        
        // Compute character data with colors
        val needsColorData = colorMode == AsciiColorModeKotlin.PRIMARY || colorMode == AsciiColorModeKotlin.FULL
        val colorData = if (needsColorData) computeBlockColors(cameraImage, metrics) else null
        
        val result = AsciiResultKotlin(metrics.numCharacterRows, metrics.numCharacterColumns)
        
        for (r in 0 until metrics.numCharacterRows) {
            for (c in 0 until metrics.numCharacterColumns) {
                val cellIndex = r * metrics.numCharacterColumns + c
                val brightness = blockAverages[cellIndex].toInt() and 0xFF
                
                // Map brightness to character index
                val charIndex = ((brightness / 256.0) * pixelChars.length).toInt().coerceIn(0, pixelChars.length - 1)
                val character = pixelChars[charIndex]
                
                // Determine color based on color mode
                val color = when (colorMode) {
                    AsciiColorModeKotlin.FIXED -> textColor
                    AsciiColorModeKotlin.PRIMARY -> {
                        val avgColor = colorData!![cellIndex]
                        computePrimaryColor(avgColor)
                    }
                    AsciiColorModeKotlin.FULL -> colorData!![cellIndex]
                }
                
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                
                result.setCharAtRowAndColumn(character, red, green, blue, r, c)
            }
        }
        
        return result
    }

    /**
     * Write ASCII art as plain text to an OutputStream.
     */
    fun writeText(cameraImage: CameraImage, out: OutputStream) {
        val metrics = textParams.getTextMetrics(cameraImage, EFFECTIVE_SIZE_FOR_TEXT_OUTPUT)
        val asciiResult = getCharacterInfo(cameraImage, pixelChars, metrics)
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        
        for (r in 0 until metrics.numCharacterRows) {
            for (c in 0 until metrics.numCharacterColumns) {
                writer.write(asciiResult.charAtRowAndColumn(r, c).toString())
            }
            writer.write("\n")
        }
        writer.flush()
    }

    /**
     * Write ASCII art as HTML to an OutputStream.
     */
    fun writeHtml(cameraImage: CameraImage, out: OutputStream) {
        val metrics = textParams.getTextMetrics(cameraImage, EFFECTIVE_SIZE_FOR_TEXT_OUTPUT)
        val asciiResult = getCharacterInfo(cameraImage, pixelChars, metrics)

        val isFixedColor = (colorMode == AsciiColorModeKotlin.FIXED)
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
        const val EFFECT_NAME = "asciiKotlin"
        const val DEFAULT_CHARACTER_COLUMNS = 120
        val EFFECTIVE_SIZE_FOR_TEXT_OUTPUT = Size(2560, 1600)

        fun fromParameters(params: Map<String, Any>): AsciiEffectKotlin {
            val colors = params.getOrElse("colors", { mapOf<String, Any>() }) as Map<String, Any>
            val textColor = intFromArgbList(
                colors.getOrElse("text", { listOf(255, 255, 255) }) as List<Int>
            )
            val backgroundColor = intFromArgbList(
                colors.getOrElse("background", { listOf(0, 0, 0) }) as List<Int>
            )
            val numCharColumns = params.getOrElse("numColumns", { DEFAULT_CHARACTER_COLUMNS }) as Int

            var pixelChars = params.getOrElse("pixelChars", { "" }) as String
            if (pixelChars.isEmpty()) {
                // Shouldn't happen, set a reasonable default.
                pixelChars = " .o8"
            }

            val colorType = try {
                AsciiColorModeKotlin.fromString(params.getOrElse("colorMode", { "fixed" }) as String)
            } catch (ex: IllegalArgumentException) {
                AsciiColorModeKotlin.FIXED
            }

            return AsciiEffectKotlin(params, numCharColumns, textColor, backgroundColor, pixelChars, colorType)
        }

        /**
         * Convert color integer to CSS hex color string.
         */
        private fun cssHexColor(color: Int): String {
            fun toHex2(x: Int): String {
                val s = Integer.toHexString(x)
                return (if (s.length > 1) s else "0$s")
            }
            val red = toHex2((color shr 16) and 0xff)
            val green = toHex2((color shr 8) and 0xff)
            val blue = toHex2(color and 0xff)
            return "#${red}${green}${blue}"
        }
    }
} 