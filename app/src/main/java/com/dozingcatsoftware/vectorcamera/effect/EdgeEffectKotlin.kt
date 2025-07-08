package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Pure Kotlin implementation of EdgeEffect that maps edge strength to colors.
 */
class EdgeEffectKotlin(
    private val effectParams: Map<String, Any> = mapOf(),
    private val minColor: Int = Color.BLACK,
    private val maxColor: Int = Color.WHITE
) : Effect {

    // Color lookup table for edge strength to color mapping
    private val colorMap = createColorMap(minColor, maxColor)

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val width = cameraImage.width()
        val height = cameraImage.height()
        val multiplier = minOf(4, maxOf(2, Math.round(width / 480f)))

        // Get YUV data directly from CameraImage
        val yuvBytes = cameraImage.getYuvBytes()!!
        return createBitmapFromYuvBytes(yuvBytes, width, height, multiplier)
    }

    var numFrames: Int = 0

    private fun createBitmapFromYuvBytes(yuvBytes: ByteArray, width: Int, height: Int, multiplier: Int): Bitmap {
        val ySize = width * height
        
        // Extract Y plane from the flattened YUV bytes
        val yData = yuvBytes.sliceArray(0 until ySize)

        val pixels = IntArray(width * height)

        // Determine optimal number of threads based on CPU cores and image size.
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 32 // Minimum rows per thread to avoid overhead
        val maxThreads = minOf(numCores, height / minRowsPerThread)
        val numThreads = maxOf(1, maxThreads)

        val t1 = System.currentTimeMillis()
        
        if (nativeLibraryLoaded) {
            // Use optimized native implementation
            processImageNativeFromYuvBytes(yData, width, height, multiplier, colorMap, pixels, numThreads)
        } else {
            // Fallback to Kotlin implementation with coroutines
            if (numThreads == 1) {
                processRows(0, height, width, height, multiplier, yData, pixels)
            } else {
                runBlocking {
                    val jobs = mutableListOf<Job>()
                    val rowsPerThread = height / numThreads
                    
                    for (threadIndex in 0 until numThreads) {
                        val startY = threadIndex * rowsPerThread
                        val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                        
                        val job = launch(Dispatchers.Default) {
                            processRows(startY, endY, width, height, multiplier, yData, pixels)
                        }
                        jobs.add(job)
                    }
                    
                    // Wait for all threads to complete
                    jobs.forEach { it.join() }
                }
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val elapsed = System.currentTimeMillis() - t1
        if (++numFrames % 30 == 0) {
            val impl = if (nativeLibraryLoaded) "native" else "Kotlin"
            Log.i(EFFECT_NAME, "Generated ${width}x${height} image in $elapsed ms with $numThreads threads ($impl)")
        }
        return bitmap
    }

    private fun processRows(
        startY: Int, 
        endY: Int, 
        width: Int, 
        height: Int, 
        multiplier: Int,
        yData: ByteArray,
        pixels: IntArray
    ) {
        for (y in startY until endY) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x

                // Calculate edge strength using Laplacian operator
                val edgeStrength = if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                    val center = yData[pixelIndex].toInt() and 0xFF
                    val surroundingSum =
                        (yData[(y - 1) * width + (x - 1)].toInt() and 0xFF) +
                        (yData[(y - 1) * width + x].toInt() and 0xFF) +
                        (yData[(y - 1) * width + (x + 1)].toInt() and 0xFF) +
                        (yData[y * width + (x - 1)].toInt() and 0xFF) +
                        (yData[y * width + (x + 1)].toInt() and 0xFF) +
                        (yData[(y + 1) * width + (x - 1)].toInt() and 0xFF) +
                        (yData[(y + 1) * width + x].toInt() and 0xFF) +
                        (yData[(y + 1) * width + (x + 1)].toInt() and 0xFF)

                    val edge = 8 * center - surroundingSum
                    (multiplier * edge).coerceIn(0, 255)
                } else {
                    0
                }

                // Map edge strength to color using lookup table
                val color = colorMap[edgeStrength]
                pixels[pixelIndex] = color
            }
        }
    }

    private fun createColorMap(minColor: Int, maxColor: Int): IntArray {
        val colorMap = IntArray(256)
        
        val r0 = Color.red(minColor)
        val g0 = Color.green(minColor)
        val b0 = Color.blue(minColor)
        val r1 = Color.red(maxColor)
        val g1 = Color.green(maxColor)
        val b1 = Color.blue(maxColor)
        
        for (i in 0 until 256) {
            val fraction = i / 255f
            val r = Math.round(r0 + (r1 - r0) * fraction)
            val g = Math.round(g0 + (g1 - g0) * fraction)
            val b = Math.round(b0 + (b1 - b0) * fraction)
            colorMap[i] = Color.argb(255, r, g, b)
        }
        
        return colorMap
    }

    companion object {
        const val EFFECT_NAME = "edge_kotlin"
        
        // Native method declaration
        private external fun processImageNativeFromYuvBytes(
            yData: ByteArray,
            width: Int,
            height: Int,
            multiplier: Int,
            colorMap: IntArray,
            outputPixels: IntArray,
            numThreads: Int
        )
        
        // Load native library
        private var nativeLibraryLoaded = false
        
        init {
            try {
                System.loadLibrary("vectorcamera_native")
                nativeLibraryLoaded = true
                Log.i(EFFECT_NAME, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(EFFECT_NAME, "Native library not available: ${e.message}")
                nativeLibraryLoaded = false
            }
        }
        
        fun fromParameters(effectParams: Map<String, Any>): EdgeEffectKotlin {
            val colors = effectParams.getOrElse("colors", { mapOf<String, Any>() }) as Map<String, Any>
            
            // Default colors (black to white)
            var minColor = Color.BLACK
            var maxColor = Color.WHITE
            
            // Parse color parameters if available
            if (colors.containsKey("minColor")) {
                val minColorList = colors["minColor"] as List<Int>
                minColor = Color.argb(255, minColorList[0], minColorList[1], minColorList[2])
            }
            if (colors.containsKey("maxColor")) {
                val maxColorList = colors["maxColor"] as List<Int>
                maxColor = Color.argb(255, maxColorList[0], maxColorList[1], maxColorList[2])
            }
            
            return EdgeEffectKotlin(effectParams, minColor, maxColor)
        }
    }
} 