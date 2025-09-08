package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.util.YuvUtils
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Enum for color component sources used in color permutation.
 */
enum class ColorComponentSource(val rsCode: Int) {
    MIN(0), RED(1), GREEN(2), BLUE(3), MAX(-1)
}

/**
 * Pure Kotlin implementation of PermuteColorEffect that swaps RGB and/or UV components.
 */
class PermuteColorEffect(
    private val effectParams: Map<String, Any> = mapOf(),
    private val redSource: ColorComponentSource = ColorComponentSource.RED,
    private val greenSource: ColorComponentSource = ColorComponentSource.GREEN,
    private val blueSource: ColorComponentSource = ColorComponentSource.BLUE,
    private val flipUV: Boolean = false
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
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 32 // Minimum rows per thread to avoid overhead
        val maxThreads = minOf(numCores, height / minRowsPerThread, Effect.MAX_NATIVE_THREADS)
        return maxOf(1, maxThreads)
    }

    /**
     * Calculate the optimal number of threads for Kotlin processing based on image dimensions.
     */
    private fun calculateOptimalKotlinThreads(height: Int): Int {
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 32 // Minimum rows per thread to avoid overhead
        val maxThreads = minOf(numCores, height / minRowsPerThread, Effect.MAX_KOTLIN_THREADS)
        return maxOf(1, maxThreads)
    }

    private fun createBitmapFromPlanes(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int): Triple<Bitmap, Int, CodeArchitecture> {
        val nativeThreads = calculateOptimalNativeThreads(height)
        val kotlinThreads = calculateOptimalKotlinThreads(height)

        val t1 = System.currentTimeMillis()
        
        // Try native implementation first
        if (nativeLibraryLoaded) {
            try {
                val nativePixels = processImageNativeFromPlanes(
                    yData, uData, vData, width, height,
                    redSource.rsCode,  // Use rsCode values that match C++ expectations
                    greenSource.rsCode,
                    blueSource.rsCode,
                    flipUV,
                    nativeThreads
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
        
        // Fall back to Kotlin implementation using individual planes directly
        val kotlinBitmap = createBitmapFromPlanesKotlin(yData, uData, vData, width, height, kotlinThreads, t1)
        return Triple(kotlinBitmap, kotlinThreads, CodeArchitecture.Kotlin)
    }
    
    private fun createBitmapFromPlanesKotlin(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int, numThreads: Int, startTime: Long): Bitmap {
        val uvWidth = (width + 1) / 2

        val pixels = IntArray(width * height)
        
        // Use Kotlin implementation with coroutines
        if (numThreads == 1) {
            processRows(0, height, width, height, yData, uData, vData, uvWidth, pixels)
        } else {
            runBlocking {
                val jobs = mutableListOf<Job>()
                val rowsPerThread = height / numThreads
                
                for (threadIndex in 0 until numThreads) {
                    val startY = threadIndex * rowsPerThread
                    val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                    
                    val job = launch(Dispatchers.Default) {
                        processRows(startY, endY, width, height, yData, uData, vData, uvWidth, pixels)
                    }
                    jobs.add(job)
                }
                
                // Wait for all threads to complete
                jobs.forEach { it.join() }
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun processRows(
        startY: Int, 
        endY: Int, 
        width: Int, 
        height: Int, 
        yData: ByteArray, 
        uData: ByteArray, 
        vData: ByteArray, 
        uvWidth: Int, 
        pixels: IntArray
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

                // flipUV rotates the color 180 degrees in UV space.
                val uu = if (flipUV) (-u and 0xFF) else u
                val vv = if (flipUV) (-v and 0xFF) else v

                // Convert YUV to RGB
                val rgb = YuvUtils.yuvToRgb(yy, uu, vv, includeAlpha = false)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                // Extract color components based on source mapping
                val outputR = extractComponent(r, g, b, redSource)
                val outputG = extractComponent(r, g, b, greenSource)
                val outputB = extractComponent(r, g, b, blueSource)

                // Create final ARGB pixel
                pixels[pixelIndex] = Color.argb(255, outputR, outputG, outputB)
            }
        }
    }

    /**
     * Extract color component based on ColorComponentSource mapping.
     */
    private fun extractComponent(r: Int, g: Int, b: Int, source: ColorComponentSource): Int {
        return when (source) {
            ColorComponentSource.RED -> r
            ColorComponentSource.GREEN -> g
            ColorComponentSource.BLUE -> b
            ColorComponentSource.MIN -> 0
            ColorComponentSource.MAX -> 255
        }
    }
    


    companion object {
        const val EFFECT_NAME = "permute_color"
        
        // Load native library
        private var nativeLibraryLoaded = false
        
        init {
            nativeLibraryLoaded = Effect.loadNativeLibrary()
        }
        
        private external fun processImageNativeFromPlanes(
            yData: ByteArray,
            uData: ByteArray,
            vData: ByteArray,
            width: Int,
            height: Int,
            redSource: Int,
            greenSource: Int,
            blueSource: Int,
            flipUV: Boolean,
            numThreads: Int
        ): IntArray?
        
        fun fromParameters(effectParams: Map<String, Any>): PermuteColorEffect {
            val redSource = ColorComponentSource.valueOf(effectParams["red"] as String)
            val greenSource = ColorComponentSource.valueOf(effectParams["green"] as String)
            val blueSource = ColorComponentSource.valueOf(effectParams["blue"] as String)
            val flipUV = effectParams.getOrElse("flipUV", { false }) as Boolean
            return PermuteColorEffect(effectParams, redSource, greenSource, blueSource, flipUV)
        }

        fun noOp() = fromParameters(mapOf(
            "red" to ColorComponentSource.RED.toString(),
            "green" to ColorComponentSource.GREEN.toString(),
            "blue" to ColorComponentSource.BLUE.toString()
        ))

        fun rgbToGbr() = fromParameters(mapOf(
            "red" to ColorComponentSource.GREEN.toString(),
            "green" to ColorComponentSource.BLUE.toString(),
            "blue" to ColorComponentSource.RED.toString()
        ))

        fun rgbToBrg() = fromParameters(mapOf(
            "red" to ColorComponentSource.BLUE.toString(),
            "green" to ColorComponentSource.RED.toString(),
            "blue" to ColorComponentSource.GREEN.toString()
        ))

        fun flipUV() = fromParameters(mapOf(
            "red" to ColorComponentSource.RED.toString(),
            "green" to ColorComponentSource.GREEN.toString(),
            "blue" to ColorComponentSource.BLUE.toString(),
            "flipUV" to true
        ))
    }
} 