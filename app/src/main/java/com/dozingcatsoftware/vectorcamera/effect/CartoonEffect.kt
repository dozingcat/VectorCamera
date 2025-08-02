package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.util.YuvUtils
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Pure Kotlin implementation of CartoonEffect that produces a cartoon-like image 
 * by blurring and reducing the color space.
 */
// TODO: Make blur radius a function of image dimensions?
class CartoonEffect(
    private val effectParams: Map<String, Any> = mapOf(),
    private val blurRadius: Int = 4
) : Effect {

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()
        
        val width = cameraImage.width()
        val height = cameraImage.height()

        // Get YUV data directly from CameraImage
        val yuvBytes = cameraImage.getYuvBytes()
        val (bitmap, threadsUsed, architectureUsed) = createBitmapFromYuvBytes(yuvBytes, width, height)
        
        val endTime = System.nanoTime()
        val metadata = ProcessedBitmapMetadata(
            codeArchitecture = architectureUsed,
            numThreads = threadsUsed,
            generationDurationNanos = endTime - startTime
        )
        
        return ProcessedBitmap(this, cameraImage, bitmap, metadata)
    }

    var numFrames: Int = 0

    private fun createBitmapFromYuvBytes(yuvBytes: ByteArray, width: Int, height: Int): Triple<Bitmap, Int, CodeArchitecture> {
        // Determine optimal number of threads based on CPU cores and image size
        val numCores = 1 // Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 32 // Minimum rows per thread to avoid overhead
        val maxThreads = minOf(numCores, height / minRowsPerThread)
        val numThreads = maxOf(1, maxThreads)

        val t1 = System.currentTimeMillis()
        
        // Try native implementation first
        if (nativeLibraryLoaded) {
            try {
                val nativePixels = processImageNativeFromYuvBytes(
                    yuvBytes, width, height, blurRadius, numThreads
                )
                if (nativePixels != null) {
                    val elapsed = System.currentTimeMillis() - t1
                    if (++numFrames % 30 == 0) {
                        Log.i(EFFECT_NAME, "Generated ${width}x${height} image in $elapsed ms with $numThreads threads (Native)")
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(nativePixels, 0, width, 0, 0, width, height)
                    return Triple(bitmap, numThreads, CodeArchitecture.Native)
                }
            } catch (e: Exception) {
                Log.w(EFFECT_NAME, "Native processing failed, falling back to Kotlin: ${e.message}")
            }
        }
        
        // Fall back to Kotlin implementation if native failed or not available
        val kotlinBitmap = createBitmapFromYuvBytesKotlin(yuvBytes, width, height, numThreads, t1)
        return Triple(kotlinBitmap, numThreads, CodeArchitecture.Kotlin)
    }
    
    private fun createBitmapFromYuvBytesKotlin(yuvBytes: ByteArray, width: Int, height: Int, numThreads: Int, startTime: Long): Bitmap {
        // Create color quantization LUT (lookup table)
        val colorLUT = createColorLUT()
        
        // Process the image
        val pixels = processImage(yuvBytes, width, height, numThreads, colorLUT)
        
        // Apply blur effect
        val blurredPixels = applyBlur(pixels, width, height, blurRadius)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(blurredPixels, 0, width, 0, 0, width, height)

        val elapsed = System.currentTimeMillis() - startTime
        if (++numFrames % 30 == 0) {
            Log.i(EFFECT_NAME, "Generated ${width}x${height} image in $elapsed ms with $numThreads threads (Kotlin)")
        }
        return bitmap
    }

    /**
     * Create a color lookup table that reduces each color component to 2 bits (4 levels).
     * Possible RGB values are (0, 85, 170, 255).
     */
    private fun createColorLUT(): IntArray {
        val lut = IntArray(256)
        for (index in 0 until 256) {
            val mapval = (index / 85.0).roundToInt() * 85
            lut[index] = mapval
        }
        return lut
    }

    private fun processImage(
        yuvBytes: ByteArray,
        width: Int,
        height: Int,
        numThreads: Int,
        colorLUT: IntArray
    ): IntArray {
        val ySize = width * height
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val uvSize = uvWidth * uvHeight

        // Extract planes from the flattened YUV bytes
        val yData = yuvBytes.sliceArray(0 until ySize)
        val uData = yuvBytes.sliceArray(ySize until ySize + uvSize)
        val vData = yuvBytes.sliceArray(ySize + uvSize until ySize + 2 * uvSize)

        val pixels = IntArray(width * height)

        // Multi-threaded processing
        if (numThreads == 1) {
            processRows(0, height, width, height, yData, uData, vData, uvWidth, pixels, colorLUT)
        } else {
            runBlocking {
                val jobs = mutableListOf<Job>()
                val rowsPerThread = height / numThreads
                
                for (threadIndex in 0 until numThreads) {
                    val startY = threadIndex * rowsPerThread
                    val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                    
                    val job = launch(Dispatchers.Default) {
                        processRows(startY, endY, width, height, yData, uData, vData, uvWidth, pixels, colorLUT)
                    }
                    jobs.add(job)
                }
                
                // Wait for all threads to complete
                jobs.forEach { it.join() }
            }
        }

        return pixels
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
        pixels: IntArray,
        colorLUT: IntArray
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
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                // Apply color quantization using LUT
                val quantizedR = colorLUT[r]
                val quantizedG = colorLUT[g]
                val quantizedB = colorLUT[b]

                // Create final ARGB pixel
                pixels[pixelIndex] = Color.argb(255, quantizedR, quantizedG, quantizedB)
            }
        }
    }

    /**
     * Apply a fast box blur to the image using separable convolution.
     */
    private fun applyBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return pixels
        
        // Apply horizontal blur first
        val horizontalBlur = applyHorizontalBlur(pixels, width, height, radius)
        
        // Apply vertical blur to the horizontally blurred image
        return applyVerticalBlur(horizontalBlur, width, height, radius)
    }
    
    private fun applyHorizontalBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val blurred = IntArray(width * height)
        val kernelSize = radius * 2 + 1
        
        for (y in 0 until height) {
            var totalR = 0
            var totalG = 0
            var totalB = 0
            
            // Initialize sliding window for first pixel
            for (kx in -radius..radius) {
                val sampleX = kx.coerceIn(0, width - 1)
                val sampleIndex = y * width + sampleX
                val samplePixel = pixels[sampleIndex]
                
                totalR += Color.red(samplePixel)
                totalG += Color.green(samplePixel)
                totalB += Color.blue(samplePixel)
            }
            
            // Apply blur to each pixel in the row using sliding window
            for (x in 0 until width) {
                val avgR = totalR / kernelSize
                val avgG = totalG / kernelSize
                val avgB = totalB / kernelSize
                
                val pixelIndex = y * width + x
                blurred[pixelIndex] = Color.argb(255, avgR, avgG, avgB)
                
                // Update sliding window for next pixel
                if (x < width - 1) {
                    // Remove leftmost pixel from window
                    val leftX = (x - radius).coerceIn(0, width - 1)
                    val leftIndex = y * width + leftX
                    val leftPixel = pixels[leftIndex]
                    totalR -= Color.red(leftPixel)
                    totalG -= Color.green(leftPixel)
                    totalB -= Color.blue(leftPixel)
                    
                    // Add rightmost pixel to window
                    val rightX = (x + radius + 1).coerceIn(0, width - 1)
                    val rightIndex = y * width + rightX
                    val rightPixel = pixels[rightIndex]
                    totalR += Color.red(rightPixel)
                    totalG += Color.green(rightPixel)
                    totalB += Color.blue(rightPixel)
                }
            }
        }
        
        return blurred
    }
    
    private fun applyVerticalBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val blurred = IntArray(width * height)
        val kernelSize = radius * 2 + 1
        
        for (x in 0 until width) {
            var totalR = 0
            var totalG = 0
            var totalB = 0
            
            // Initialize sliding window for first pixel
            for (ky in -radius..radius) {
                val sampleY = ky.coerceIn(0, height - 1)
                val sampleIndex = sampleY * width + x
                val samplePixel = pixels[sampleIndex]
                
                totalR += Color.red(samplePixel)
                totalG += Color.green(samplePixel)
                totalB += Color.blue(samplePixel)
            }
            
            // Apply blur to each pixel in the column using sliding window
            for (y in 0 until height) {
                val avgR = totalR / kernelSize
                val avgG = totalG / kernelSize
                val avgB = totalB / kernelSize
                
                val pixelIndex = y * width + x
                blurred[pixelIndex] = Color.argb(255, avgR, avgG, avgB)
                
                // Update sliding window for next pixel
                if (y < height - 1) {
                    // Remove topmost pixel from window
                    val topY = (y - radius).coerceIn(0, height - 1)
                    val topIndex = topY * width + x
                    val topPixel = pixels[topIndex]
                    totalR -= Color.red(topPixel)
                    totalG -= Color.green(topPixel)
                    totalB -= Color.blue(topPixel)
                    
                    // Add bottommost pixel to window
                    val bottomY = (y + radius + 1).coerceIn(0, height - 1)
                    val bottomIndex = bottomY * width + x
                    val bottomPixel = pixels[bottomIndex]
                    totalR += Color.red(bottomPixel)
                    totalG += Color.green(bottomPixel)
                    totalB += Color.blue(bottomPixel)
                }
            }
        }
        
        return blurred
    }

    companion object {
        const val EFFECT_NAME = "cartoon"
        
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
        
        private external fun processImageNativeFromYuvBytes(
            yuvBytes: ByteArray,
            width: Int,
            height: Int,
            blurRadius: Int,
            numThreads: Int
        ): IntArray?
        
        fun fromParameters(effectParams: Map<String, Any>): CartoonEffect {
            val blurRadius = effectParams.getOrElse("blurRadius", { 4 }) as Int
            return CartoonEffect(effectParams, blurRadius)
        }
        
        fun withBlurRadius(radius: Int) = fromParameters(mapOf("blurRadius" to radius))
        
        /**
         * Test method to verify the LUT functionality produces the expected quantization.
         */
        fun testLUT() {
            val effect = CartoonEffect()
            val lut = effect.createColorLUT()
            
            // Test key values
            assert(lut[0] == 0) { "LUT[0] should be 0, got ${lut[0]}" }
            assert(lut[42] == 0) { "LUT[42] should be 0, got ${lut[42]}" }
            assert(lut[43] == 85) { "LUT[43] should be 85, got ${lut[43]}" }
            assert(lut[85] == 85) { "LUT[85] should be 85, got ${lut[85]}" }
            assert(lut[127] == 85) { "LUT[127] should be 85, got ${lut[127]}" }
            assert(lut[128] == 170) { "LUT[128] should be 170, got ${lut[128]}" }
            assert(lut[170] == 170) { "LUT[170] should be 170, got ${lut[170]}" }
            assert(lut[212] == 170) { "LUT[212] should be 170, got ${lut[212]}" }
            assert(lut[213] == 255) { "LUT[213] should be 255, got ${lut[213]}" }
            assert(lut[255] == 255) { "LUT[255] should be 255, got ${lut[255]}" }
            
            Log.i(EFFECT_NAME, "LUT test passed - color quantization is working correctly")
        }
    }
} 