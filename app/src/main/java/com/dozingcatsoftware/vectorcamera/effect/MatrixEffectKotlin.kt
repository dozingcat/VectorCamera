package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import android.util.Size
import com.dozingcatsoftware.util.YuvUtils
import com.dozingcatsoftware.util.scaleToTargetSize
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.*
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation

/**
 * Kotlin-based raindrop animation state.
 */
class RaindropKotlin(val x: Int, val y: Int, val startTimestamp: Long) {
    var prevLength = -1L
}

/**
 * Character grid metrics for text layout.
 */
class TextMetricsKotlin(
    val isPortrait: Boolean, 
    val outputSize: Size, 
    val charPixelSize: Size,
    val numCharacterRows: Int, 
    val numCharacterColumns: Int
)

/**
 * Text layout parameters.
 */
class TextParamsKotlin(
    private val numPreferredCharColumns: Int,
    private val minCharWidth: Int,
    private val charHeightOverWidth: Double
) {
    fun getTextMetrics(cameraImage: CameraImage, maxOutputSize: Size): TextMetricsKotlin {
        val isPortrait = cameraImage.orientation.portrait
        val targetOutputSize = scaleToTargetSize(cameraImage.size(), maxOutputSize)
        
        val numPixelsForColumns = if (isPortrait) targetOutputSize.height else targetOutputSize.width
        val numCharColumns = minOf(numPreferredCharColumns, numPixelsForColumns / minCharWidth)
        val charPixelWidth = numPixelsForColumns / numCharColumns
        val charPixelHeight = (charPixelWidth * charHeightOverWidth).roundToInt()
        val numPixelsForRows = if (isPortrait) targetOutputSize.width else targetOutputSize.height
        val numCharRows = numPixelsForRows / charPixelHeight
        
        val actualOutputSize = if (isPortrait) 
            Size(numCharRows * charPixelHeight, numCharColumns * charPixelWidth)
        else 
            Size(numCharColumns * charPixelWidth, numCharRows * charPixelHeight)
            
        return TextMetricsKotlin(isPortrait, actualOutputSize, Size(charPixelWidth, charPixelHeight),
                numCharRows, numCharColumns)
    }
}

/**
 * Pure Kotlin implementation of MatrixEffect that creates a "digital rain" Matrix-style effect
 * with Japanese characters, animated raindrops, and optional edge detection.
 */
class MatrixEffectKotlin(
    private val effectParams: Map<String, Any> = mapOf()
) : Effect {

    private val numPreferredCharColumns = 
        effectParams.getOrElse("numColumns", { DEFAULT_CHARACTER_COLUMNS }) as Int
    private val computeEdges = effectParams.get("edges") == true
    private val textColor = effectParams.getOrElse("textColor", { 0x00ff00 }) as Int
    private val maxTextRed = (textColor shr 16) and 0xff
    private val maxTextGreen = (textColor shr 8) and 0xff
    private val maxTextBlue = textColor and 0xff

    // Animation parameters
    private val textParams = TextParamsKotlin(numPreferredCharColumns, 10, 1.8)
    private val raindrops = HashSet<RaindropKotlin>()
    private var raindropDecayMillis = 2000L
    private var raindropMillisPerTick = 200L
    private var newRaindropProbPerFrame = 0.05
    private var maxRaindropLength = 15
    private var charChangeProbPerFrame = 1.0 / 300
    
    // Character grid state
    private var characterIndices: IntArray? = null
    private var characterColors: IntArray? = null
    private var characterTemplate: Bitmap? = null
    private var lastCharPixelSize: Size? = null

    // Native method declarations
    private external fun computeBlockBrightnessNative(
        yData: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        numCharColumns: Int,
        numCharRows: Int,
        isPortrait: Boolean,
        blockAverages: ByteArray,
        numThreads: Int
    )
    
    private external fun applyEdgeDetectionNative(
        input: ByteArray,
        output: ByteArray,
        width: Int,
        height: Int,
        multiplier: Int,
        numThreads: Int
    )
    

    
    private external fun isNativeAvailable(): Boolean

    // Static block to load native library
    companion object {
        private var nativeLibraryLoaded = false
        
        init {
            try {
                System.loadLibrary("vectorcamera_native")
                nativeLibraryLoaded = true
                Log.i("MatrixEffectKotlin", "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.w("MatrixEffectKotlin", "Native library not available, using Kotlin implementation")
                nativeLibraryLoaded = false
            }
        }
        
        const val EFFECT_NAME = "matrixKotlin"
        const val DEFAULT_CHARACTER_COLUMNS = 120

        // Japanese hiragana and katakana characters, and (reversed) English letters and numbers
        const val MATRIX_NORMAL_CHARS =
            "ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕ" +
            "ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
        const val MATRIX_REVERSED_CHARS = "QWERTYUIOPASDFGHJKLZXCVBNM1234567890"
        const val NUM_MATRIX_CHARS = MATRIX_NORMAL_CHARS.length + MATRIX_REVERSED_CHARS.length

        fun fromParameters(params: Map<String, Any>): MatrixEffectKotlin {
            return MatrixEffectKotlin(params)
        }
    }

    override fun effectName() = EFFECT_NAME
    override fun effectParameters() = effectParams

    var numFrames: Int = 0

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val t1 = System.currentTimeMillis()
        
        val rand = Random(cameraImage.timestamp)
        val metrics = textParams.getTextMetrics(cameraImage, cameraImage.displaySize)
        
        // Compute grid brightness values (with optional edge detection)
        val blockAverages = computeBlockAverages(cameraImage, metrics)
        
        // Update character grid and raindrop animations
        updateGridCharacters(metrics, rand)
        updateGridColors(cameraImage, metrics, blockAverages, rand)
        
        // Create character template bitmap if needed
        updateCharTemplateBitmap(metrics.charPixelSize)
        
        // Render final bitmap
        val resultBitmap = renderFinalBitmap(cameraImage, metrics)
        
        val elapsed = System.currentTimeMillis() - t1
        if (++numFrames % 30 == 0) {
            val impl = if (nativeLibraryLoaded) "native" else "Kotlin"
            Log.i(EFFECT_NAME, "Generated ${metrics.outputSize.width}x${metrics.outputSize.height} Matrix image in $elapsed ms ($impl)")
        }
        
        return resultBitmap
    }

    /**
     * Compute average brightness for each character cell, with optional edge detection.
     */
    private fun computeBlockAverages(cameraImage: CameraImage, metrics: TextMetricsKotlin): ByteArray {
        val width = cameraImage.width()
        val height = cameraImage.height()
        val yuvBytes = cameraImage.getYuvBytes()!!
        val ySize = width * height
        val yData = yuvBytes.sliceArray(0 until ySize)
        
        val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
        val blockAverages = ByteArray(numCells)
        
        // Use native implementation if available for better performance
        if (nativeLibraryLoaded) {
            try {
                val numCores = Runtime.getRuntime().availableProcessors()
                // Use threading only for larger grids to avoid overhead
                val minRowsForThreading = 16
                val numThreads = if (metrics.numCharacterRows >= minRowsForThreading) {
                    minOf(numCores, metrics.numCharacterRows / 8).coerceAtLeast(1)
                } else {
                    1 // Single thread for small grids
                }
                
                computeBlockBrightnessNative(
                    yData, width, height,
                    metrics.numCharacterColumns, metrics.numCharacterRows,
                    metrics.isPortrait, blockAverages, numThreads
                )
            } catch (e: Exception) {
                Log.w(EFFECT_NAME, "Native block brightness computation failed, falling back to Kotlin", e)
                computeBlockAveragesKotlin(yData, width, height, metrics, blockAverages)
            }
        } else {
            computeBlockAveragesKotlin(yData, width, height, metrics, blockAverages)
        }
        
        // Apply edge detection if enabled
        return if (computeEdges) {
            applyEdgeDetection(blockAverages, metrics.numCharacterColumns, metrics.numCharacterRows)
        } else {
            blockAverages
        }
    }
    
    /**
     * Kotlin fallback for block brightness computation.
     */
    private fun computeBlockAveragesKotlin(
        yData: ByteArray, 
        width: Int, 
        height: Int, 
        metrics: TextMetricsKotlin, 
        blockAverages: ByteArray
    ) {
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
    }
    
    /**
     * Apply simple edge detection to the brightness grid.
     */
    private fun applyEdgeDetection(input: ByteArray, width: Int, height: Int): ByteArray {
        val result = ByteArray(input.size)
        val multiplier = 4 // Edge strength multiplier
        
        // Use native implementation if available for better performance
        if (nativeLibraryLoaded) {
            try {
                val numCores = Runtime.getRuntime().availableProcessors()
                // Use threading only for larger grids to avoid overhead
                val minRowsForThreading = 32
                val numThreads = if (height >= minRowsForThreading) {
                    minOf(numCores, height / 16).coerceAtLeast(1)
                } else {
                    1 // Single thread for small grids
                }
                
                applyEdgeDetectionNative(input, result, width, height, multiplier, numThreads)
            } catch (e: Exception) {
                Log.w(EFFECT_NAME, "Native edge detection failed, falling back to Kotlin", e)
                applyEdgeDetectionKotlin(input, result, width, height, multiplier)
            }
        } else {
            applyEdgeDetectionKotlin(input, result, width, height, multiplier)
        }
        
        return result
    }
    
    /**
     * Kotlin fallback for edge detection.
     */
    private fun applyEdgeDetectionKotlin(input: ByteArray, result: ByteArray, width: Int, height: Int, multiplier: Int) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                
                if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                    val center = input[index].toInt() and 0xFF
                    val surroundingSum =
                        (input[(y - 1) * width + (x - 1)].toInt() and 0xFF) +
                        (input[(y - 1) * width + x].toInt() and 0xFF) +
                        (input[(y - 1) * width + (x + 1)].toInt() and 0xFF) +
                        (input[y * width + (x - 1)].toInt() and 0xFF) +
                        (input[y * width + (x + 1)].toInt() and 0xFF) +
                        (input[(y + 1) * width + (x - 1)].toInt() and 0xFF) +
                        (input[(y + 1) * width + x].toInt() and 0xFF) +
                        (input[(y + 1) * width + (x + 1)].toInt() and 0xFF)
                    
                    val edge = 8 * center - surroundingSum
                    result[index] = (multiplier * edge).coerceIn(0, 255).toByte()
                } else {
                    result[index] = 0
                }
            }
        }
    }

    /**
     * Update the character indices for each grid cell.
     */
    private fun updateGridCharacters(metrics: TextMetricsKotlin, rand: Random) {
        val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
        val prevIndices = characterIndices
        
        characterIndices = IntArray(numCells)
        
        if (prevIndices != null && prevIndices.size == numCells) {
            // Copy previous characters and change a few randomly
            System.arraycopy(prevIndices, 0, characterIndices!!, 0, numCells)
            
            val numChanged = (numCells * charChangeProbPerFrame).toInt()
            for (i in 0 until numChanged) {
                val offset = rand.nextInt(numCells)
                characterIndices!![offset] = rand.nextInt(NUM_MATRIX_CHARS)
            }
        } else {
            // Initialize with random characters
            for (i in 0 until numCells) {
                characterIndices!![i] = rand.nextInt(NUM_MATRIX_CHARS)
            }
        }
    }

    /**
     * Update character colors based on brightness and raindrop animations.
     */
    private fun updateGridColors(
        ci: CameraImage, 
        metrics: TextMetricsKotlin, 
        blockAverages: ByteArray, 
        rand: Random
    ) {
        val numCells = metrics.numCharacterColumns * metrics.numCharacterRows
        val prevColors = characterColors
        
        characterColors = IntArray(numCells)
        
        if (prevColors == null || prevColors.size != numCells) {
            raindrops.clear()
        }
        
        // Set base colors from brightness
        for (i in 0 until numCells) {
            val fraction = (blockAverages[i].toInt() and 0xFF) / 255.0
            val red = (fraction * maxTextRed).toInt().coerceIn(0, 255)
            val green = (fraction * maxTextGreen).toInt().coerceIn(0, 255)
            val blue = (fraction * maxTextBlue).toInt().coerceIn(0, 255)
            characterColors!![i] = Color.argb(255, red, green, blue)
        }
        
        // Update raindrops
        val raindropLifetimeMillis = maxRaindropLength * raindropMillisPerTick + raindropDecayMillis
        
        // Remove expired raindrops
        val rit = raindrops.iterator()
        while (rit.hasNext()) {
            val drop = rit.next()
            val diff = ci.timestamp - drop.startTimestamp
            if (diff < 0 || diff > raindropLifetimeMillis) {
                rit.remove()
            }
        }
        
        // Add new raindrops
        val maxRaindrops = minOf(10, metrics.numCharacterColumns / 10)
        if (raindrops.size < maxRaindrops && rand.nextDouble() < newRaindropProbPerFrame) {
            raindrops.add(RaindropKotlin(
                rand.nextInt(metrics.numCharacterColumns),
                rand.nextInt(metrics.numCharacterRows),
                ci.timestamp
            ))
        }
        
        // Apply raindrop effects
        for (drop in raindrops) {
            val dir = if (ci.orientation.yFlipped) -1 else 1
            val ticks = (ci.timestamp - drop.startTimestamp) / raindropMillisPerTick
            val length = minOf(maxRaindropLength.toLong(), ticks)
            val growing = (length > drop.prevLength)
            drop.prevLength = length
            
            for (dy in 0..length) {
                val y = drop.y + (dy * dir).toInt()
                if (y < 0 || y >= metrics.numCharacterRows) break
                
                // Change character if raindrop is growing
                if (growing && dy == length) {
                    val cellIndex = y * metrics.numCharacterColumns + drop.x
                    if (cellIndex in 0 until numCells) {
                        characterIndices!![cellIndex] = rand.nextInt(NUM_MATRIX_CHARS)
                    }
                }
                
                // Set bright color for raindrop trail
                val millisSinceActive = ci.timestamp - (drop.startTimestamp + dy * raindropMillisPerTick)
                val fraction = 1.0 - (millisSinceActive.toDouble() / raindropLifetimeMillis)
                if (fraction > 0) {
                    val cellIndex = y * metrics.numCharacterColumns + drop.x
                    if (cellIndex in 0 until numCells) {
                        val brightness = (255 * fraction).roundToInt().coerceIn(0, 255)
                        val isHead = (dy == ticks)
                        
                        val red = if (isHead) 255 else (fraction * maxTextRed).toInt().coerceIn(0, 255)
                        val green = if (isHead) 255 else (fraction * maxTextGreen).toInt().coerceIn(0, 255)
                        val blue = if (isHead) 255 else (fraction * maxTextBlue).toInt().coerceIn(0, 255)
                        
                        characterColors!![cellIndex] = Color.argb(255, red, green, blue)
                    }
                }
            }
        }
    }

    /**
     * Create or update the character template bitmap with all Matrix characters.
     */
    private fun updateCharTemplateBitmap(charPixelSize: Size) {
        // Only recreate if size changed
        if (characterTemplate != null && charPixelSize == lastCharPixelSize) {
            return
        }
        
        lastCharPixelSize = charPixelSize
        val charBitmapWidth = charPixelSize.width * NUM_MATRIX_CHARS
        
        val paint = Paint().apply {
            textSize = charPixelSize.height * 5f / 6
            color = Color.WHITE  // Use white for template, then color with filters
            isAntiAlias = false
        }
        
        characterTemplate = Bitmap.createBitmap(charBitmapWidth, charPixelSize.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(characterTemplate!!)
        canvas.drawColor(Color.BLACK)
        
        // Draw normal characters
        val normalChars = MATRIX_NORMAL_CHARS
        for (i in normalChars.indices) {
            canvas.drawText(
                normalChars[i].toString(),
                (i * charPixelSize.width).toFloat(),
                charPixelSize.height - 1f,
                paint
            )
        }
        
        // Draw reversed characters (mirrored horizontally)
        canvas.withScale(-1f, 1f) {
            val reversedChars = MATRIX_REVERSED_CHARS
            for (i in reversedChars.indices) {
                drawText(
                    reversedChars[i].toString(),
                    -((normalChars.length + i + 1) * charPixelSize.width).toFloat(),
                    charPixelSize.height - 1f,
                    paint
                )
            }
        }
    }

    /**
     * Create a colored character bitmap by replacing non-black pixels with the desired color.
     * This mimics the RenderScript logic: if (pixel is non-black) use textColor else use original
     * 
     * NOTE: Character coloring is kept in Kotlin due to high JNI overhead when called 3600+ times per frame
     */
    private fun createColoredCharBitmap(template: Bitmap, srcRect: Rect, color: Int, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        
        // Always use Kotlin for character coloring to avoid JNI overhead on frequent calls
        createColoredCharBitmapKotlin(template, srcRect, color, width, height, pixels)
        
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * Kotlin fallback for character coloring.
     */
    private fun createColoredCharBitmapKotlin(template: Bitmap, srcRect: Rect, color: Int, width: Int, height: Int, pixels: IntArray) {
        val templatePixels = IntArray(srcRect.width() * srcRect.height())
        
        // Extract pixels from the template character
        template.getPixels(templatePixels, 0, srcRect.width(), srcRect.left, srcRect.top, srcRect.width(), srcRect.height())
        
        // Scale to target size if needed and apply color replacement
        for (y in 0 until height) {
            for (x in 0 until width) {
                val srcX = (x * srcRect.width()) / width
                val srcY = (y * srcRect.height()) / height
                val srcIndex = srcY * srcRect.width() + srcX
                
                if (srcIndex < templatePixels.size) {
                    val templatePixel = templatePixels[srcIndex]
                    val red = Color.red(templatePixel)
                    val green = Color.green(templatePixel)
                    val blue = Color.blue(templatePixel)
                    
                    // RenderScript logic: if non-black, use textColor, else use original (black)
                    val resultPixel = if (red > 0 || green > 0 || blue > 0) {
                        color
                    } else {
                        Color.BLACK
                    }
                    pixels[y * width + x] = resultPixel
                } else {
                    pixels[y * width + x] = Color.BLACK
                }
            }
        }
    }

    /**
     * Render the final bitmap by drawing characters into a grid.
     */
    private fun renderFinalBitmap(cameraImage: CameraImage, metrics: TextMetricsKotlin): Bitmap {
        val resultBitmap = Bitmap.createBitmap(
            metrics.outputSize.width, 
            metrics.outputSize.height, 
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(resultBitmap)
        canvas.drawColor(Color.BLACK)
        
        val template = characterTemplate!!
        val charWidth = metrics.charPixelSize.width
        val charHeight = metrics.charPixelSize.height
        
        for (blockY in 0 until metrics.numCharacterRows) {
            for (blockX in 0 until metrics.numCharacterColumns) {
                val cellIndex = blockY * metrics.numCharacterColumns + blockX
                if (cellIndex >= characterIndices!!.size || cellIndex >= characterColors!!.size) continue
                
                val charIndex = characterIndices!![cellIndex]
                val color = characterColors!![cellIndex]
                
                // Calculate source rectangle in character template
                val srcLeft = charIndex * charWidth
                val srcRect = Rect(srcLeft, 0, srcLeft + charWidth, charHeight)
                
                // Calculate destination rectangle in output
                val dstLeft: Int
                val dstTop: Int
                val dstWidth: Int
                val dstHeight: Int
                
                if (metrics.isPortrait) {
                    // In portrait mode, characters are rotated
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
                
                val dstRect = Rect(dstLeft, dstTop, dstLeft + dstWidth, dstTop + dstHeight)
                
                // Create a colored character bitmap by replacing white pixels with the desired color
                val coloredChar = createColoredCharBitmap(template, srcRect, color, charWidth, charHeight)
                
                if (metrics.isPortrait) {
                    // Portrait mode: rotate character 90 degrees
                    canvas.withTranslation(
                        (dstLeft + dstWidth / 2).toFloat(),
                        (dstTop + dstHeight / 2).toFloat()
                    ) {
                        rotate(-90f)
                        if (cameraImage.orientation.xFlipped) scale(-1f, 1f)
                        if (cameraImage.orientation.yFlipped) scale(1f, -1f)
                        translate(-charWidth / 2f, -charHeight / 2f)
                        drawBitmap(coloredChar, null, Rect(0, 0, charWidth, charHeight), null)
                    }
                } else {
                    // Landscape mode: draw character normally with flipping
                    canvas.withTranslation(dstLeft.toFloat(), dstTop.toFloat()) {
                        if (cameraImage.orientation.xFlipped) scale(-1f, 1f)
                        if (cameraImage.orientation.yFlipped) scale(1f, -1f)
                        drawBitmap(coloredChar, null, Rect(0, 0, charWidth, charHeight), null)
                    }
                }
            }
        }
        
        return resultBitmap
    }

} 