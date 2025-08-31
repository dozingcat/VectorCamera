package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.util.YuvUtils
import com.dozingcatsoftware.util.resizeImageBytes
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * This effect simulates oil painting brush strokes by:
 * 1. Analyzing local contrast to determine brush size
 * 2. Finding the most dominant color in circular neighborhoods
 * 3. Applying the dominant color with slight variations for texture
 */
class OilPaintingEffect(
    private val effectParams: Map<String, Any> = mapOf(),
    private val brushSize: Int = 8,          // Maximum brush radius in pixels
    private val levels: Int = 32,            // Color quantization levels (reduces palette)
    private val contrastSensitivity: Float = 2.0f  // How much contrast affects brush size
) : Effect {

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()
        
        val width = cameraImage.width()
        val height = cameraImage.height()

        // Get individual plane data directly
        val yData = cameraImage.getYBytes()
        val uData = cameraImage.getUBytes()
        val vData = cameraImage.getVBytes()
        val (bitmap, threadsUsed, architectureUsed) = createBitmapFromPlanes(yData, uData, vData, width, height)
        
        val endTime = System.nanoTime()
        val metadata = ProcessedBitmapMetadata(
            codeArchitecture = architectureUsed,
            numThreads = threadsUsed,
            generationDurationNanos = endTime - startTime
        )
        
        return ProcessedBitmap(this, cameraImage, bitmap, metadata)
    }

    private fun calculateOptimalNativeThreads(height: Int): Int {
        // More threads don't seem to help in C++.
        return 1
    }

    private fun calculateOptimalKotlinThreads(height: Int): Int {
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 64 // Larger minimum due to complex per-pixel operations
        val maxThreads = minOf(numCores, height / minRowsPerThread, Effect.MAX_KOTLIN_THREADS)
        return maxOf(1, maxThreads)
    }

    private fun createBitmapFromPlanes(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int): Triple<Bitmap, Int, CodeArchitecture> {
        // Scale down input image for better performance while maintaining quality
        val (scaledWidth, scaledHeight, scaleFactor) = calculateOptimalSize(width, height)
        val (scaledYData, scaledUData, scaledVData) = downsampleYuvData(yData, uData, vData, width, height, scaledWidth, scaledHeight)

        if (nativeLibraryLoaded) {
            val nativeThreads = calculateOptimalNativeThreads(scaledHeight)
            try {
                val nativePixels = processImageNativeFromPlanes(
                    scaledYData, scaledUData, scaledVData, scaledWidth, scaledHeight, brushSize, levels, contrastSensitivity, nativeThreads
                )
                if (nativePixels != null) {
                    val resultBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
                    resultBitmap.setPixels(nativePixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
                    return Triple(resultBitmap, nativeThreads, CodeArchitecture.Native)
                }
            } catch (e: Exception) {
                Log.w(EFFECT_NAME, "Native processing failed, falling back to Kotlin: ${e.message}")
            }
        }

        // Fall back to Kotlin implementation with scaled data
        val kotlinThreads = calculateOptimalKotlinThreads(scaledHeight)
        val resultBitmap = createBitmapFromPlanesKotlin(scaledYData, scaledUData, scaledVData, scaledWidth, scaledHeight, kotlinThreads)
        return Triple(resultBitmap, kotlinThreads, CodeArchitecture.Kotlin)
    }
    
    private fun createBitmapFromPlanesKotlin(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int, numThreads: Int): Bitmap {
        // First pass: Convert YUV to RGB and quantize colors
        val rgbPixels = convertYuvToRgbQuantized(yData, uData, vData, width, height)
        
        // Second pass: Apply oil painting effect
        val oilPaintedPixels = applyOilPaintingEffect(rgbPixels, width, height, numThreads)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(oilPaintedPixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Calculate optimal processing size to keep largest dimension under 720 pixels
     * for better performance while maintaining quality.
     */
    private fun calculateOptimalSize(width: Int, height: Int): Triple<Int, Int, Float> {
        val maxDimension = 720
        val currentMax = maxOf(width, height)
        
        if (currentMax <= maxDimension) {
            // No scaling needed
            return Triple(width, height, 1.0f)
        }
        
        val scaleFactor = maxDimension.toFloat() / currentMax
        val scaledWidth = (width * scaleFactor).roundToInt()
        val scaledHeight = (height * scaleFactor).roundToInt()
        
        return Triple(scaledWidth, scaledHeight, scaleFactor)
    }

    /**
     * Downsample YUV data to a smaller resolution using efficient sampling.
     * Maintains aspect ratio and YUV 4:2:0 format.
     */
    private fun downsampleYuvData(
        yData: ByteArray,
        uData: ByteArray,
        vData: ByteArray,
        originalWidth: Int,
        originalHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Triple<ByteArray, ByteArray, ByteArray> {
        if (originalWidth == targetWidth && originalHeight == targetHeight) {
            return Triple(yData, uData, vData)
        }
        val targetUVWidth = (targetWidth + 1) / 2
        val targetUVHeight = (targetHeight + 1) / 2
        val originalUVWidth = (originalWidth + 1) / 2
        val originalUVHeight = (originalHeight + 1) / 2

        val scaledYData = resizeImageBytes(yData, originalWidth, originalHeight, targetWidth, targetHeight)
        val scaledUData = resizeImageBytes(uData, originalUVWidth, originalUVHeight, targetUVWidth, targetUVHeight)
        val scaledVData = resizeImageBytes(vData, originalUVWidth, originalUVHeight, targetUVWidth, targetUVHeight)

        return Triple(scaledYData, scaledUData, scaledVData)
    }

    /**
     * Convert YUV to RGB with color quantization for oil painting look
     */
    private fun convertYuvToRgbQuantized(
        yData: ByteArray,
        uData: ByteArray,
        vData: ByteArray,
        width: Int,
        height: Int
    ): IntArray {
        val uvWidth = (width + 1) / 2
        val pixels = IntArray(width * height)
        val quantizationStep = 256 / levels

        // Could use threads here but probably not worth it.
        processYuvToRgbRows(0, height, width, height, yData, uData, vData, uvWidth, pixels, quantizationStep)
        return pixels
    }

    private fun processYuvToRgbRows(
        startY: Int, 
        endY: Int, 
        width: Int, 
        height: Int, 
        yData: ByteArray, 
        uData: ByteArray, 
        vData: ByteArray, 
        uvWidth: Int, 
        pixels: IntArray,
        quantizationStep: Int
    ) {
        for (y in startY until endY) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x

                // Get Y value
                val yy = yData[pixelIndex].toInt() and 0xFF

                // Get U and V values (subsampled)
                val uvX = x / 2
                val uvY = y / 2
                val uvIndex = uvY * uvWidth + uvX
                val u = uData[uvIndex].toInt() and 0xFF
                val v = vData[uvIndex].toInt() and 0xFF

                // Convert YUV to RGB
                val rgb = YuvUtils.yuvToRgb(yy, u, v, includeAlpha = false)
                var r = (rgb shr 16) and 0xFF
                var g = (rgb shr 8) and 0xFF
                var b = rgb and 0xFF

                // Quantize colors for oil painting effect
                r = (r / quantizationStep) * quantizationStep
                g = (g / quantizationStep) * quantizationStep
                b = (b / quantizationStep) * quantizationStep

                pixels[pixelIndex] = Color.argb(255, r, g, b)
            }
        }
    }

    /**
     * Apply oil painting effect by finding dominant colors in circular neighborhoods
     */
    private fun applyOilPaintingEffect(
        sourcePixels: IntArray,
        width: Int,
        height: Int,
        numThreads: Int
    ): IntArray {
        val resultPixels = IntArray(width * height)

        // Multi-threaded processing
        if (numThreads == 1) {
            processOilPaintingRows(0, height, width, height, sourcePixels, resultPixels)
        } else {
            runBlocking {
                val jobs = mutableListOf<Job>()
                val rowsPerThread = height / numThreads
                
                for (threadIndex in 0 until numThreads) {
                    val startY = threadIndex * rowsPerThread
                    val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                    
                    val job = launch(Dispatchers.Default) {
                        processOilPaintingRows(startY, endY, width, height, sourcePixels, resultPixels)
                    }
                    jobs.add(job)
                }
                
                jobs.forEach { it.join() }
            }
        }

        return resultPixels
    }

    private fun processOilPaintingRows(
        startY: Int,
        endY: Int,
        width: Int,
        height: Int,
        sourcePixels: IntArray,
        resultPixels: IntArray
    ) {
        // Thread-local storage for incremental updates
        val colorCounts = mutableMapOf<Int, Int>()
        
        for (y in startY until endY) {
            // Clear color counts at start of each row
            colorCounts.clear()
            var previousBrushSize = -1
            
            for (x in 0 until width) {
                val pixelIndex = y * width + x
                
                // Calculate adaptive brush size based on local contrast
                val localBrushSize = calculateAdaptiveBrushSize(sourcePixels, x, y, width, height)
                
                // Use incremental updates if brush size hasn't changed
                if (localBrushSize == previousBrushSize && x > 0) {
                    // Incremental update: slide brush horizontally
                    updateColorCounts(
                        sourcePixels, x, y, width, height, localBrushSize,
                        colorCounts, ColorCountUpdateType.Incremental
                    )
                } else {
                    // Full recalculation: brush size changed or first pixel in row
                    updateColorCounts(
                        sourcePixels, x, y, width, height, localBrushSize,
                        colorCounts, ColorCountUpdateType.Full
                    )
                }
                
                // Find dominant color using current counts
                val dominantColor = findDominantColorFromCounts(
                    colorCounts, sourcePixels[pixelIndex]
                )
                
                resultPixels[pixelIndex] = dominantColor
                previousBrushSize = localBrushSize
            }
        }
    }

    private fun brightnessForRgb(rgb: Int): Double {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r * 0.299 + g * 0.587 + b * 0.114
    }

    /**
     * Calculate brush size based on local contrast - smaller brushes for high detail areas
     */
    private fun calculateAdaptiveBrushSize(
        pixels: IntArray,
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int
    ): Int {
        val sampleRadius = 3
        var totalVariance = 0.0
        var sampleCount = 0

        val centerPixel = pixels[centerY * width + centerX]
        val centerBrightness = brightnessForRgb(centerPixel)

        // Sample surrounding pixels to measure local contrast
        for (dy in -sampleRadius..sampleRadius) {
            for (dx in -sampleRadius..sampleRadius) {
                val sampleX = centerX + dx
                val sampleY = centerY + dy
                
                if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                    val samplePixel = pixels[sampleY * width + sampleX]
                    val sampleBrightness = brightnessForRgb(samplePixel)
                    
                    val diff = abs(sampleBrightness - centerBrightness)
                    totalVariance += diff * diff
                    sampleCount++
                }
            }
        }

        val avgVariance = if (sampleCount > 0) totalVariance / sampleCount else 0.0
        val normalizedVariance = (avgVariance / (255.0 * 255.0)).coerceIn(0.0, 1.0)
        
        // High contrast = smaller brush, low contrast = larger brush
        val adaptiveFactor = 1.0 - (normalizedVariance * contrastSensitivity).coerceIn(0.0, 0.8)
        return (brushSize * adaptiveFactor).roundToInt().coerceIn(2, brushSize)
    }

    enum class ColorCountUpdateType {Full, Incremental};

    /**
     * Incrementally update color counts when sliding brush horizontally
     */
    private fun updateColorCounts(
        pixels: IntArray,
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        radius: Int,
        colorCounts: MutableMap<Int, Int>,
        updateType: ColorCountUpdateType
    ) {
        val pattern = getBrushPattern(radius)
        
        if (updateType == ColorCountUpdateType.Full) {
            // Initialize with full brush pattern
            colorCounts.clear()
            for ((dx, dy) in pattern.offsets) {
                val sampleX = centerX + dx
                val sampleY = centerY + dy
                
                if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                    val samplePixel = pixels[sampleY * width + sampleX]
                    colorCounts[samplePixel] = colorCounts.getOrElse(samplePixel, {0}) + 1
                }
            }
        } else {
            // Remove left edge pixels
            for ((dx, dy) in pattern.leftEdgeToRemove) {
                val sampleX = centerX + dx
                val sampleY = centerY + dy
                
                if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                    val samplePixel = pixels[sampleY * width + sampleX]
                    val currentCount = colorCounts.getOrElse(samplePixel, {0})
                    if (currentCount > 1) {
                        colorCounts[samplePixel] = currentCount - 1
                    } else {
                        colorCounts.remove(samplePixel)
                    }
                }
            }
            
            // Add right edge pixels
            for ((dx, dy) in pattern.rightEdgeToAdd) {
                val sampleX = centerX + dx
                val sampleY = centerY + dy
                
                if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                    val samplePixel = pixels[sampleY * width + sampleX]
                    colorCounts[samplePixel] = colorCounts.getOrElse(samplePixel, {0}) + 1
                }
            }
        }
    }
    
    /**
     * Find dominant color using current color counts (after incremental update)
     */
    private fun findDominantColorFromCounts(
        colorCounts: Map<Int, Int>,
        fallbackColor: Int
    ): Int {
        return colorCounts.maxByOrNull { it.value }?.key ?: fallbackColor
    }
    
    /**
     * Pre-computed brush pattern for incremental sliding optimization
     */
    private data class BrushPattern(
        val offsets: List<Pair<Int, Int>>,
        val leftEdgeToRemove: List<Pair<Int, Int>>,
        val rightEdgeToAdd: List<Pair<Int, Int>>
    )
    
    companion object {
        const val EFFECT_NAME = "oil_painting"
        
        // Cache of pre-computed brush patterns for different radii
        private val brushPatterns = mutableMapOf<Int, BrushPattern>()
        
        /**
         * Initialize brush pattern for a given radius with incremental update patterns
         */
        private fun getBrushPattern(radius: Int): BrushPattern {
            return brushPatterns.getOrPut(radius) {
                if (radius == 0) {
                    BrushPattern(
                        offsets = listOf(Pair(0, 0)),
                        leftEdgeToRemove = emptyList(),
                        rightEdgeToAdd = emptyList()
                    )
                } else {
                    val radiusSquared = radius * radius
                    val offsets = mutableListOf<Pair<Int, Int>>()
                    
                    // Compute all offsets within the circular brush
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val distanceSquared = dx * dx + dy * dy
                            if (distanceSquared <= radiusSquared) {
                                offsets.add(Pair(dx, dy))
                            }
                        }
                    }

                    // Compute incremental update patterns
                    val leftEdgeToRemove = mutableListOf<Pair<Int, Int>>()
                    val rightEdgeToAdd = mutableListOf<Pair<Int, Int>>()
                    
                    for ((dx, dy) in offsets) {
                        // Left edge: points that would go outside if shifted left
                        val leftDistanceSquared = (dx - 1) * (dx - 1) + dy * dy
                        if (leftDistanceSquared > radiusSquared) {
                            leftEdgeToRemove.add(Pair(dx - 1, dy))
                        }
                        
                        // Right edge: current points that are on the right boundary
                        val rightDistanceSquared = (dx + 1) * (dx + 1) + dy * dy
                        if (rightDistanceSquared > radiusSquared) {
                            rightEdgeToAdd.add(Pair(dx, dy))
                        }
                    }
                    
                    BrushPattern(offsets, leftEdgeToRemove, rightEdgeToAdd)
                }
            }
        }
        
        // Load native library
        private var nativeLibraryLoaded = false
        
        init {
            try {
                System.loadLibrary("vectorcamera_native")
                nativeLibraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(EFFECT_NAME, "Failed to load native library, using Kotlin implementation: ${e.message}")
                nativeLibraryLoaded = false
            }
        }
        
        private external fun processImageNativeFromPlanes(
            yData: ByteArray,
            uData: ByteArray,
            vData: ByteArray,
            width: Int,
            height: Int,
            brushSize: Int,
            levels: Int,
            contrastSensitivity: Float,
            numThreads: Int
        ): IntArray?
        
        fun fromParameters(effectParams: Map<String, Any>): OilPaintingEffect {
            val brushSize = (effectParams.getOrElse("brushSize", { 8 }) as Number).toInt()
            val levels = (effectParams.getOrElse("levels", { 32 }) as Number).toInt()
            val contrastSensitivity = (effectParams.getOrElse("contrastSensitivity", { 2.0f }) as Number).toFloat()
            
            return OilPaintingEffect(effectParams, brushSize, levels, contrastSensitivity)
        }
        
        // Factory methods for different oil painting styles
        fun subtle() = fromParameters(mapOf(
            "brushSize" to 4,
            "levels" to 48,
            "contrastSensitivity" to 1.5f
        ))
        
        fun standard() = fromParameters(mapOf(
            "brushSize" to 8,
            "levels" to 32,
            "contrastSensitivity" to 2.0f
        ))
        
        fun heavy() = fromParameters(mapOf(
            "brushSize" to 12,
            "levels" to 20,
            "contrastSensitivity" to 3.0f
        ))
        
        fun impressionist() = fromParameters(mapOf(
            "brushSize" to 15,
            "levels" to 16,
            "contrastSensitivity" to 2.5f
        ))
    }
}
