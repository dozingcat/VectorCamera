package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Pure Kotlin implementation of PermuteColorEffect that swaps RGB and/or UV components.
 */
class PermuteColorEffectKotlin(
    private val effectParams: Map<String, Any> = mapOf(),
    private val redSource: ColorComponentSource = ColorComponentSource.RED,
    private val greenSource: ColorComponentSource = ColorComponentSource.GREEN,
    private val blueSource: ColorComponentSource = ColorComponentSource.BLUE,
    private val flipUV: Boolean = false
) : Effect {

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val width = cameraImage.width()
        val height = cameraImage.height()

        // Get YUV data directly from CameraImage
        val yuvBytes = cameraImage.getYuvBytes()!!
        return createBitmapFromYuvBytes(yuvBytes, width, height)
    }

    var numFrames: Int = 0

    private fun createBitmapFromYuvBytes(yuvBytes: ByteArray, width: Int, height: Int): Bitmap {
        val ySize = width * height
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val uvSize = uvWidth * uvHeight

        // Extract planes from the flattened YUV bytes
        val yData = yuvBytes.sliceArray(0 until ySize)
        val uData = yuvBytes.sliceArray(ySize until ySize + uvSize)
        val vData = yuvBytes.sliceArray(ySize + uvSize until ySize + 2 * uvSize)

        val pixels = IntArray(width * height)

        // Determine optimal number of threads based on CPU cores and image size
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 32 // Minimum rows per thread to avoid overhead
        val maxThreads = minOf(numCores, height / minRowsPerThread)
        val numThreads = maxOf(1, maxThreads)

        val t1 = System.currentTimeMillis()
        
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

        val elapsed = System.currentTimeMillis() - t1
        if (++numFrames % 30 == 0) {
            Log.i(EFFECT_NAME, "Generated ${width}x${height} image in $elapsed ms with $numThreads threads (Kotlin)")
        }
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

                // Apply UV flipping if requested
                val uu = if (flipUV) v else u
                val vv = if (flipUV) u else v

                // Convert YUV to RGB
                val rgb = yuvToRgb(yy, uu, vv)
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
    
    /**
     * Convert YUV to RGB using standard conversion formulas.
     * Based on ITU-R BT.601 conversion equations.
     */
    private fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        val yValue = y.toFloat()
        val uValue = (u - 128).toFloat()
        val vValue = (v - 128).toFloat()

        val red = (yValue + 1.370705f * vValue).roundToInt().coerceIn(0, 255)
        val green = (yValue - 0.698001f * vValue - 0.337633f * uValue).roundToInt().coerceIn(0, 255)
        val blue = (yValue + 1.732446f * uValue).roundToInt().coerceIn(0, 255)

        return (red shl 16) or (green shl 8) or blue
    }

    companion object {
        const val EFFECT_NAME = "permute_colors_kotlin"
        
        fun fromParameters(effectParams: Map<String, Any>): PermuteColorEffectKotlin {
            val redSource = ColorComponentSource.valueOf(effectParams["red"] as String)
            val greenSource = ColorComponentSource.valueOf(effectParams["green"] as String)
            val blueSource = ColorComponentSource.valueOf(effectParams["blue"] as String)
            val flipUV = effectParams.getOrElse("flipUV", { false }) as Boolean
            return PermuteColorEffectKotlin(effectParams, redSource, greenSource, blueSource, flipUV)
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