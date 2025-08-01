package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.util.Size

import com.dozingcatsoftware.util.scaleToTargetSize
import com.dozingcatsoftware.vectorcamera.*

import java.util.*
import kotlin.math.*


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
class MatrixEffect(
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
    

    
    private external fun renderCharacterGridNative(
        templatePixels: IntArray,
        templateWidth: Int,
        templateHeight: Int,
        charWidth: Int,
        charHeight: Int,
        characterIndices: IntArray,
        characterColors: IntArray,
        numCharColumns: Int,
        numCharRows: Int,
        isPortrait: Boolean,
        xFlipped: Boolean,
        yFlipped: Boolean,
        outputWidth: Int,
        outputHeight: Int,
        outputPixels: IntArray,
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
                Log.i("MatrixEffect", "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.w("MatrixEffect", "Native library not available, using Kotlin implementation")
                nativeLibraryLoaded = false
            }
        }
        
        const val EFFECT_NAME = "matrix"
        const val DEFAULT_CHARACTER_COLUMNS = 120

        // Japanese hiragana and katakana characters, and (reversed) English letters and numbers
        const val MATRIX_NORMAL_CHARS =
            "ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕ" +
            "ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
        const val MATRIX_REVERSED_CHARS = "QWERTYUIOPASDFGHJKLZXCVBNM1234567890"
        const val NUM_MATRIX_CHARS = MATRIX_NORMAL_CHARS.length + MATRIX_REVERSED_CHARS.length

        fun fromParameters(params: Map<String, Any>): MatrixEffect {
            return MatrixEffect(params)
        }
    }

    override fun effectName() = EFFECT_NAME
    override fun effectParameters() = effectParams

    var numFrames: Int = 0

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()
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
        
        val threadsUsed = if (nativeLibraryLoaded) {
            val numCores = Runtime.getRuntime().availableProcessors()
            val minRowsForThreading = 8
            if (metrics.numCharacterRows >= minRowsForThreading) {
                minOf(numCores, metrics.numCharacterRows / 4).coerceAtLeast(1)
            } else {
                1
            }
        } else {
            1 // Kotlin fallback is single-threaded
        }
        
        // Render final bitmap
        val resultBitmap = renderFinalBitmap(cameraImage, metrics, threadsUsed)
        
        val elapsed = System.currentTimeMillis() - t1
        if (++numFrames % 30 == 0) {
            val impl = if (nativeLibraryLoaded) "native" else "Kotlin"
            Log.i(EFFECT_NAME, "Generated ${metrics.outputSize.width}x${metrics.outputSize.height} Matrix image in $elapsed ms ($impl)")
        }
        
        val endTime = System.nanoTime()
        val metadata = ProcessedBitmapMetadata(
            codeArchitecture = if (nativeLibraryLoaded) CodeArchitecture.Native else CodeArchitecture.Kotlin,
            numThreads = threadsUsed,
            generationDurationNanos = endTime - startTime
        )
        
        return ProcessedBitmap(this, cameraImage, resultBitmap, metadata)
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
        canvas.save()
        canvas.scale(-1f, 1f)
        val reversedChars = MATRIX_REVERSED_CHARS
        for (i in reversedChars.indices) {
            canvas.drawText(
                reversedChars[i].toString(),
                -((normalChars.length + i + 1) * charPixelSize.width).toFloat(),
                charPixelSize.height - 1f,
                paint
            )
        }
        canvas.restore()
    }

    /**
     * Render the final bitmap using native bulk character rendering.
     * This replaces thousands of individual character operations with one multi-threaded C++ call.
     */
    private fun renderFinalBitmap(cameraImage: CameraImage, metrics: TextMetricsKotlin, numThreads: Int): Bitmap {
        val resultBitmap = Bitmap.createBitmap(
            metrics.outputSize.width, 
            metrics.outputSize.height, 
            Bitmap.Config.ARGB_8888
        )
        
        val template = characterTemplate!!
        val charWidth = metrics.charPixelSize.width
        val charHeight = metrics.charPixelSize.height
        val outputPixels = IntArray(metrics.outputSize.width * metrics.outputSize.height)
        
        // Use native bulk rendering if available for massive performance improvement
        if (nativeLibraryLoaded) {
            try {
                // Get template bitmap pixels once
                val templatePixels = IntArray(template.width * template.height)
                template.getPixels(templatePixels, 0, template.width, 0, 0, template.width, template.height)
                
                // Use the pre-calculated thread count passed as parameter
                
                // Render entire character grid in native code with multi-threading
                renderCharacterGridNative(
                    templatePixels, template.width, template.height,
                    charWidth, charHeight,
                    characterIndices!!, characterColors!!,
                    metrics.numCharacterColumns, metrics.numCharacterRows,
                    metrics.isPortrait, cameraImage.orientation.xFlipped, cameraImage.orientation.yFlipped,
                    metrics.outputSize.width, metrics.outputSize.height,
                    outputPixels, numThreads
                )
            } catch (e: Exception) {
                Log.w(EFFECT_NAME, "Native character grid rendering failed, falling back to Kotlin", e)
                renderFinalBitmapKotlin(cameraImage, metrics, template, charWidth, charHeight, outputPixels)
            }
        } else {
            renderFinalBitmapKotlin(cameraImage, metrics, template, charWidth, charHeight, outputPixels)
        }
        
        // Create bitmap from rendered pixels
        resultBitmap.setPixels(outputPixels, 0, metrics.outputSize.width, 0, 0, 
                              metrics.outputSize.width, metrics.outputSize.height)
        return resultBitmap
    }
    
    /**
     * Kotlin fallback for character grid rendering.
     */
    private fun renderFinalBitmapKotlin(
        cameraImage: CameraImage, 
        metrics: TextMetricsKotlin, 
        template: Bitmap, 
        charWidth: Int, 
        charHeight: Int, 
        outputPixels: IntArray
    ) {
        // Fill with black background
        outputPixels.fill(Color.BLACK)
        
        val templatePixels = IntArray(template.width * template.height)
        template.getPixels(templatePixels, 0, template.width, 0, 0, template.width, template.height)
        
        for (blockY in 0 until metrics.numCharacterRows) {
            for (blockX in 0 until metrics.numCharacterColumns) {
                val cellIndex = blockY * metrics.numCharacterColumns + blockX
                if (cellIndex >= characterIndices!!.size || cellIndex >= characterColors!!.size) continue
                
                val charIndex = characterIndices!![cellIndex]
                val color = characterColors!![cellIndex]
                
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
                            val red = Color.red(templatePixel)
                            val green = Color.green(templatePixel)
                            val blue = Color.blue(templatePixel)
                            
                            val outputColor = if (red > 0 || green > 0 || blue > 0) color else Color.BLACK
                            
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

} 