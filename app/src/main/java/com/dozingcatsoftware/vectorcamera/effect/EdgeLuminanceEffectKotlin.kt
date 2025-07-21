package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.util.Log
import com.dozingcatsoftware.util.YuvUtils
import com.dozingcatsoftware.vectorcamera.CameraImage
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Pure Kotlin implementation of EdgeLuminanceEffect.
 * Preserves the "color" of each pixel as given by its U and V values, but replaces its
 * brightness (Y value) with its edge strength.
 */
class EdgeLuminanceEffectKotlin : Effect {

    // Native method declarations
    private external fun processRowsNative(
        startY: Int, 
        endY: Int, 
        width: Int, 
        height: Int, 
        multiplier: Int,
        yData: ByteArray, 
        uData: ByteArray, 
        vData: ByteArray, 
        uvWidth: Int, 
        pixels: IntArray
    )
    
    // Optimized native method that handles threading internally
    private external fun processImageNative(
        width: Int,
        height: Int,
        multiplier: Int,
        yData: ByteArray,
        uData: ByteArray,
        vData: ByteArray,
        uvWidth: Int,
        pixels: IntArray,
        numThreads: Int
    )
    
    private external fun isNativeAvailable(): Boolean

    // Static block to load native library
    companion object {
        const val EFFECT_NAME = "edge_luminance_kotlin"
        
        private var nativeLibraryLoaded = false
        
        init {
            try {
                System.loadLibrary("vectorcamera_native")
                nativeLibraryLoaded = true
                Log.i(EFFECT_NAME, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(EFFECT_NAME, "Native library not available, using Kotlin implementation", e)
                nativeLibraryLoaded = false
            }
        }

        fun fromParameters(params: Map<String, Any>): EdgeLuminanceEffectKotlin {
            return EdgeLuminanceEffectKotlin()
        }
    }

    override fun effectName() = EFFECT_NAME

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
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val uvSize = uvWidth * uvHeight

        // Extract planes from the flattened YUV bytes
        val yData = yuvBytes.sliceArray(0 until ySize)
        val uData = yuvBytes.sliceArray(ySize until ySize + uvSize)
        val vData = yuvBytes.sliceArray(ySize + uvSize until ySize + 2 * uvSize)

        val pixels = IntArray(width * height)

        // Determine optimal number of threads based on CPU cores and image size.
        // On a Pixel 8a, using 9 threads renders in ~110ms and 1 thread takes ~330ms.
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 32 // Minimum rows per thread to avoid overhead
        val maxThreads = min(numCores, height / minRowsPerThread)
        val numThreads = maxOf(1, maxThreads)

        val t1 = System.currentTimeMillis()
        
        if (nativeLibraryLoaded) {
            // Use optimized native implementation that handles threading internally
            processImageNative(width, height, multiplier, yData, uData, vData, uvWidth, pixels, numThreads)
        } else {
            // Fallback to Kotlin implementation with coroutines
            if (numThreads == 1) {
                processRows(0, height, width, height, multiplier, yData, uData, vData, uvWidth, pixels)
            } else {
                runBlocking {
                    val jobs = mutableListOf<Job>()
                    val rowsPerThread = height / numThreads
                    
                    for (threadIndex in 0 until numThreads) {
                        val startY = threadIndex * rowsPerThread
                        val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                        
                        val job = launch(Dispatchers.Default) {
                            processRows(startY, endY, width, height, multiplier, yData, uData, vData, uvWidth, pixels)
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
        uData: ByteArray, 
        vData: ByteArray, 
        uvWidth: Int, 
        pixels: IntArray
    ) {
        for (y in startY until endY) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x

                // Calculate edge strength using Laplacian operator
                val edgeStrength = if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                    val center = yData[pixelIndex].toInt() and 0xFF
                    // Trying to optimize this by reducing multiplications doesn't help.
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

                // Get U and V values (subsampled)
                val uvX = x / 2
                val uvY = y / 2
                val uvIndex = uvY * uvWidth + uvX
                val u = uData[uvIndex].toInt() and 0xFF
                val v = vData[uvIndex].toInt() and 0xFF

                // Convert YUV to RGB using edge strength as Y value
                val rgb = YuvUtils.yuvToRgb(edgeStrength, u, v, includeAlpha = true)
                pixels[pixelIndex] = rgb
            }
        }
    }
    



} 