package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.util.YuvUtils
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Oil Painting Effect that creates a painterly appearance by averaging colors
 * in circular neighborhoods of varying sizes based on local image characteristics.
 * 
 * The effect simulates oil painting brush strokes by:
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

    /**
     * Calculate the optimal number of threads for native processing based on image dimensions.
     */
    private fun calculateOptimalNativeThreads(height: Int): Int {
        return 1
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 64 // Larger minimum due to complex per-pixel operations
        val maxThreads = minOf(numCores, height / minRowsPerThread, Effect.MAX_NATIVE_THREADS)
        return maxOf(1, maxThreads)
    }

    /**
     * Calculate the optimal number of threads for Kotlin processing based on image dimensions.
     */
    private fun calculateOptimalKotlinThreads(height: Int): Int {
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 64 // Larger minimum due to complex per-pixel operations
        val maxThreads = minOf(numCores, height / minRowsPerThread, Effect.MAX_KOTLIN_THREADS)
        return maxOf(1, maxThreads)
    }

    private fun createBitmapFromPlanes(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int): Triple<Bitmap, Int, CodeArchitecture> {
        val nativeThreads = calculateOptimalNativeThreads(height)
        val kotlinThreads = calculateOptimalKotlinThreads(height)

        // Try native implementation first
        if (nativeLibraryLoaded) {
            try {
                val nativePixels = processImageNativeFromPlanes(
                    yData, uData, vData, width, height, brushSize, levels, contrastSensitivity, nativeThreads
                )
                if (nativePixels != null) {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(nativePixels, 0, width, 0, 0, width, height)
                    return Triple(bitmap, nativeThreads, CodeArchitecture.Native)
                }
            } catch (e: Exception) {
                Log.w(EFFECT_NAME, "Native processing failed, falling back to Kotlin: ${e.message}")
            }
        }
        
        // Fall back to Kotlin implementation
        val kotlinBitmap = createBitmapFromPlanesKotlin(yData, uData, vData, width, height, kotlinThreads)
        return Triple(kotlinBitmap, kotlinThreads, CodeArchitecture.Kotlin)
    }
    
    private fun createBitmapFromPlanesKotlin(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int, numThreads: Int): Bitmap {
        // First pass: Convert YUV to RGB and quantize colors
        val rgbPixels = convertYuvToRgbQuantized(yData, uData, vData, width, height, numThreads)
        
        // Second pass: Apply oil painting effect
        val oilPaintedPixels = applyOilPaintingEffect(rgbPixels, width, height, numThreads)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(oilPaintedPixels, 0, width, 0, 0, width, height)
        return bitmap
    }



    /**
     * Convert YUV to RGB with color quantization for oil painting look
     */
    private fun convertYuvToRgbQuantized(
        yData: ByteArray,
        uData: ByteArray,
        vData: ByteArray,
        width: Int,
        height: Int,
        numThreads: Int
    ): IntArray {
        val uvWidth = (width + 1) / 2
        val pixels = IntArray(width * height)
        val quantizationStep = 256 / levels

        // Multi-threaded processing
        if (numThreads == 1) {
            processYuvToRgbRows(0, height, width, height, yData, uData, vData, uvWidth, pixels, quantizationStep)
        } else {
            runBlocking {
                val jobs = mutableListOf<Job>()
                val rowsPerThread = height / numThreads
                
                for (threadIndex in 0 until numThreads) {
                    val startY = threadIndex * rowsPerThread
                    val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                    
                    val job = launch(Dispatchers.Default) {
                        processYuvToRgbRows(startY, endY, width, height, yData, uData, vData, uvWidth, pixels, quantizationStep)
                    }
                    jobs.add(job)
                }
                
                jobs.forEach { it.join() }
            }
        }

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
        for (y in startY until endY) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x
                
                // Calculate adaptive brush size based on local contrast
                val localBrushSize = calculateAdaptiveBrushSize(sourcePixels, x, y, width, height)
                
                // Find dominant color in circular neighborhood
                val dominantColor = findDominantColorInRadius(sourcePixels, x, y, width, height, localBrushSize)
                
                resultPixels[pixelIndex] = dominantColor
            }
        }
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
        val centerBrightness = Color.red(centerPixel) * 0.299 + Color.green(centerPixel) * 0.587 + Color.blue(centerPixel) * 0.114

        // Sample surrounding pixels to measure local contrast
        for (dy in -sampleRadius..sampleRadius) {
            for (dx in -sampleRadius..sampleRadius) {
                val sampleX = centerX + dx
                val sampleY = centerY + dy
                
                if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                    val samplePixel = pixels[sampleY * width + sampleX]
                    val sampleBrightness = Color.red(samplePixel) * 0.299 + Color.green(samplePixel) * 0.587 + Color.blue(samplePixel) * 0.114
                    
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

    /**
     * Find the most dominant color in a circular neighborhood
     */
    private fun findDominantColorInRadius(
        pixels: IntArray,
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        radius: Int
    ): Int {
        val colorCounts = mutableMapOf<Int, Int>()
        val radiusSquared = radius * radius

        // Sample pixels in circular area
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared <= radiusSquared) {
                    val sampleX = centerX + dx
                    val sampleY = centerY + dy
                    
                    if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                        val samplePixel = pixels[sampleY * width + sampleX]
                        colorCounts[samplePixel] = colorCounts.getOrDefault(samplePixel, 0) + 1
                    }
                }
            }
        }

        // Find most frequent color
        return colorCounts.maxByOrNull { it.value }?.key ?: pixels[centerY * width + centerX]
    }

    companion object {
        const val EFFECT_NAME = "oil_painting"
        
        // Load native library
        private var nativeLibraryLoaded = false
        
        init {
            try {
                System.loadLibrary("vectorcamera_native")
                nativeLibraryLoaded = true
                Log.i(EFFECT_NAME, "Native library loaded successfully")
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
