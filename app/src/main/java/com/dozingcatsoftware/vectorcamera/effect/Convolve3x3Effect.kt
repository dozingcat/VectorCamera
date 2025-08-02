package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Pure Kotlin implementation of Convolve3x3Effect that performs a 3x3 convolution operation 
 * on the brightness of the input image.
 */
class Convolve3x3Effect(
    private val effectParams: Map<String, Any> = mapOf(),
    private val coefficients: FloatArray,
    private val colorMap: IntArray,
    private val backgroundFn: (CameraImage, Canvas, RectF) -> Unit = { _, _, _ -> }
) : Effect {

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        backgroundFn.invoke(cameraImage, canvas, rect)
    }

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()
        
        val width = cameraImage.width()
        val height = cameraImage.height()

        // Get YUV data directly from CameraImage
        val yuvBytes = cameraImage.getYuvBytes()!!
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

    private fun createBitmapFromYuvBytes(yuvBytes: ByteArray, width: Int, height: Int): Triple<Bitmap, Int, CodeArchitecture> {
        val nativeThreads = calculateOptimalNativeThreads(height)
        val kotlinThreads = calculateOptimalKotlinThreads(height)

        val t1 = System.currentTimeMillis()
        
        // Try native implementation first
        if (nativeLibraryLoaded) {
            try {
                val nativePixels = processImageNativeFromYuvBytes(
                    yuvBytes, width, height, coefficients, colorMap, nativeThreads
                )
                if (nativePixels != null) {
                    val elapsed = System.currentTimeMillis() - t1
                    if (++numFrames % 30 == 0) {
                        Log.i(EFFECT_NAME, "Generated ${width}x${height} image in $elapsed ms with $nativeThreads threads (Native)")
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(nativePixels, 0, width, 0, 0, width, height)
                    return Triple(bitmap, nativeThreads, CodeArchitecture.Native)
                }
            } catch (e: Exception) {
                Log.w(EFFECT_NAME, "Native processing failed, falling back to Kotlin: ${e.message}")
            }
        }
        
        // Fall back to Kotlin implementation if native failed or not available
        val kotlinBitmap = createBitmapFromYuvBytesKotlin(yuvBytes, width, height, kotlinThreads, t1)
        return Triple(kotlinBitmap, kotlinThreads, CodeArchitecture.Kotlin)
    }
    
    private fun createBitmapFromYuvBytesKotlin(yuvBytes: ByteArray, width: Int, height: Int, numThreads: Int, startTime: Long): Bitmap {
        val ySize = width * height
        // Extract Y plane from the flattened YUV bytes (we only need luminance for convolution)
        val yData = yuvBytes.sliceArray(0 until ySize)

        // First apply convolution to get brightness values
        val convolvedData = applyConvolution(yData, width, height, numThreads)
        
        // Then map convolved values to colors
        val pixels = IntArray(width * height)
        mapToColors(convolvedData, pixels, numThreads)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val elapsed = System.currentTimeMillis() - startTime
        if (++numFrames % 30 == 0) {
            Log.i(EFFECT_NAME, "Generated ${width}x${height} image in $elapsed ms with $numThreads threads (Kotlin)")
        }
        return bitmap
    }

    /**
     * Apply 3x3 convolution to the Y (brightness) data.
     * Each pixel is replaced by the weighted sum of itself and its 8 neighbors.
     */
    private fun applyConvolution(yData: ByteArray, width: Int, height: Int, numThreads: Int): ByteArray {
        val convolvedData = ByteArray(width * height)

        // Multi-threaded convolution processing
        if (numThreads == 1) {
            processConvolutionRows(0, height, width, height, yData, convolvedData, coefficients)
        } else {
            runBlocking {
                val jobs = mutableListOf<Job>()
                val rowsPerThread = height / numThreads
                
                for (threadIndex in 0 until numThreads) {
                    val startY = threadIndex * rowsPerThread
                    val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                    
                    val job = launch(Dispatchers.Default) {
                        processConvolutionRows(startY, endY, width, height, yData, convolvedData, coefficients)
                    }
                    jobs.add(job)
                }
                
                // Wait for all threads to complete
                jobs.forEach { it.join() }
            }
        }

        return convolvedData
    }

    private fun processConvolutionRows(
        startY: Int, 
        endY: Int, 
        width: Int, 
        height: Int, 
        yData: ByteArray, 
        convolvedData: ByteArray, 
        coeffs: FloatArray
    ) {
        for (y in startY until endY) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x
                
                // Apply 3x3 convolution
                var sum = 0f
                var coeffIndex = 0
                
                // Process 3x3 neighborhood
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        
                        // Handle boundary conditions by clamping coordinates
                        val clampedX = nx.coerceIn(0, width - 1)
                        val clampedY = ny.coerceIn(0, height - 1)
                        val neighborIndex = clampedY * width + clampedX
                        
                        val pixelValue = yData[neighborIndex].toInt() and 0xFF
                        sum += coeffs[coeffIndex] * pixelValue
                        coeffIndex++
                    }
                }
                
                // Clamp result to valid byte range and store
                val result = sum.roundToInt().coerceIn(0, 255)
                convolvedData[pixelIndex] = result.toByte()
            }
        }
    }

    /**
     * Map convolved brightness values to colors using the color map.
     */
    private fun mapToColors(convolvedData: ByteArray, pixels: IntArray, numThreads: Int) {
        if (numThreads == 1) {
            processColorMappingRows(0, convolvedData.size, convolvedData, pixels, colorMap)
        } else {
            runBlocking {
                val jobs = mutableListOf<Job>()
                val pixelsPerThread = convolvedData.size / numThreads
                
                for (threadIndex in 0 until numThreads) {
                    val startIndex = threadIndex * pixelsPerThread
                    val endIndex = if (threadIndex == numThreads - 1) convolvedData.size else (threadIndex + 1) * pixelsPerThread
                    
                    val job = launch(Dispatchers.Default) {
                        processColorMappingRows(startIndex, endIndex, convolvedData, pixels, colorMap)
                    }
                    jobs.add(job)
                }
                
                // Wait for all threads to complete
                jobs.forEach { it.join() }
            }
        }
    }

    private fun processColorMappingRows(
        startIndex: Int,
        endIndex: Int,
        convolvedData: ByteArray,
        pixels: IntArray,
        colorMap: IntArray
    ) {
        for (i in startIndex until endIndex) {
            val brightness = convolvedData[i].toInt() and 0xFF
            pixels[i] = colorMap[brightness]
        }
    }

    companion object {
        const val EFFECT_NAME = "convolve3x3"
        
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
            coefficients: FloatArray,
            colorMap: IntArray,
            numThreads: Int
        ): IntArray?
        
        fun fromParameters(effectParams: Map<String, Any>): Convolve3x3Effect {
            // Parse coefficients
            val coeffs = FloatArray(9)
            val paramList = effectParams["coefficients"] as List<Number>
            if (paramList.size != 9) {
                throw IllegalArgumentException("Expected 9 coefficients, got ${paramList.size}")
            }
            for (i in 0 until 9) {
                coeffs[i] = paramList[i].toFloat()
            }
            
            // Parse color scheme parameters (backwards compatibility)
            val colorParams = effectParams.getOrElse("colors", { effectParams }) as Map<String, Any>
            
            when (colorParams["type"]) {
                "fixed" -> {
                    val minColor = parseColorFromList(colorParams, "minColor", "minEdgeColor")
                    val maxColor = parseColorFromList(colorParams, "maxColor", "maxEdgeColor")
                    val colorMap = createFixedColorMap(minColor, maxColor)
                    return Convolve3x3Effect(effectParams, coeffs, colorMap)
                }
                "linear_gradient" -> {
                    val minColor = parseColorFromList(colorParams, "minColor", "minEdgeColor")
                    val gradientStartColor = parseColorFromList(colorParams, "gradientStartColor")
                    val gradientEndColor = parseColorFromList(colorParams, "gradientEndColor")
                    val backgroundFn = fun(_: CameraImage, canvas: Canvas, rect: RectF) {
                        val paint = Paint()
                        paint.shader = LinearGradient(
                            rect.left, rect.top, rect.right, rect.bottom,
                            gradientStartColor or 0xFF000000.toInt(), 
                            gradientEndColor or 0xFF000000.toInt(),
                            Shader.TileMode.MIRROR
                        )
                        canvas.drawRect(rect, paint)
                    }
                    val alphaMap = createAlphaMap(minColor)
                    return Convolve3x3Effect(effectParams, coeffs, alphaMap, backgroundFn)
                }
                "radial_gradient" -> {
                    val minColor = parseColorFromList(colorParams, "minColor", "minEdgeColor")
                    val centerColor = parseColorFromList(colorParams, "centerColor")
                    val outerColor = parseColorFromList(colorParams, "outerColor")
                    val backgroundFn = fun(_: CameraImage, canvas: Canvas, rect: RectF) {
                        val paint = Paint()
                        paint.shader = RadialGradient(
                            rect.width() / 2, rect.height() / 2,
                            maxOf(rect.width(), rect.height()) / 2f,
                            centerColor or 0xFF000000.toInt(), 
                            outerColor or 0xFF000000.toInt(), 
                            Shader.TileMode.MIRROR
                        )
                        canvas.drawRect(rect, paint)
                    }
                    val alphaMap = createAlphaMap(minColor)
                    return Convolve3x3Effect(effectParams, coeffs, alphaMap, backgroundFn)
                }
                "grid_gradient" -> {
                    val minColor = parseColorFromList(colorParams, "minColor")
                    val gridColors = colorParams["grid"] as List<List<List<Int>>>
                    val speedX = (colorParams.getOrElse("speedX", { 0 }) as Number).toInt()
                    val speedY = (colorParams.getOrElse("speedY", { 0 }) as Number).toInt()
                    val sizeX = (colorParams.getOrElse("sizeX", { 1 }) as Number).toFloat()
                    val sizeY = (colorParams.getOrElse("sizeY", { 1 }) as Number).toFloat()
                    val pixelsPerCell = (colorParams.getOrElse("pixelsPerCell", { Animated2dGradient.DEFAULT_PIXELS_PER_CELL }) as Number).toInt()
                    
                    val gradient = Animated2dGradient(gridColors, speedX, speedY, sizeX, sizeY, pixelsPerCell)
                    val backgroundFn = fun(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
                        gradient.drawToCanvas(canvas, rect, cameraImage.timestamp)
                    }
                    val alphaMap = createAlphaMap(minColor)
                    return Convolve3x3Effect(effectParams, coeffs, alphaMap, backgroundFn)
                }
                else -> {
                    // Default to black to white gradient
                    val colorMap = createFixedColorMap(Color.BLACK, Color.WHITE)
                    return Convolve3x3Effect(effectParams, coeffs, colorMap)
                }
            }
        }
        
        private fun parseColorFromList(params: Map<String, Any>, vararg keys: String): Int {
            for (key in keys) {
                if (params.containsKey(key)) {
                    val colorList = params[key] as List<Int>
                    return Color.argb(255, colorList[0], colorList[1], colorList[2])
                }
            }
            throw IllegalArgumentException("Color key not found: ${keys.joinToString(", ")}")
        }
        
        /**
         * Create a color map that linearly interpolates between minColor and maxColor over 256 values
         */
        private fun createFixedColorMap(minColor: Int, maxColor: Int): IntArray {
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
        
        /**
         * Create an alpha map where 0 is fully opaque (showing minColor) and 255 is fully transparent.
         * This is used for gradient effects where the background is drawn first.
         */
        private fun createAlphaMap(minColor: Int): IntArray {
            val alphaMap = IntArray(256)
            val r = Color.red(minColor)
            val g = Color.green(minColor)
            val b = Color.blue(minColor)
            
            for (i in 0 until 256) {
                // For gradient effects: 0 = fully opaque (show foreground color), 255 = fully transparent (show gradient)
                val alpha = 255 - i
                alphaMap[i] = Color.argb(alpha, r, g, b)
            }
            
            return alphaMap
        }
        
        // Factory methods for common convolution kernels
        
        /** Identity kernel - no change */
        fun identity() = fromParameters(mapOf(
            "coefficients" to listOf(0, 0, 0, 0, 1, 0, 0, 0, 0),
            "colors" to mapOf(
                "type" to "fixed",
                "minColor" to listOf(0, 0, 0),
                "maxColor" to listOf(255, 255, 255)
            )
        ))
        
        /** Emboss effect */
        fun emboss() = fromParameters(mapOf(
            "coefficients" to listOf(8, 4, 0, 4, 1, -4, 0, -4, -8),
            "colors" to mapOf(
                "type" to "fixed",
                "minColor" to listOf(0, 0, 0),
                "maxColor" to listOf(255, 255, 255)
            )
        ))
        
        /** Edge detection (Laplacian) */
        fun edgeDetection() = fromParameters(mapOf(
            "coefficients" to listOf(0, -1, 0, -1, 4, -1, 0, -1, 0),
            "colors" to mapOf(
                "type" to "fixed",
                "minColor" to listOf(0, 0, 0),
                "maxColor" to listOf(255, 255, 255)
            )
        ))
        
        /** Sharpen filter */
        fun sharpen() = fromParameters(mapOf(
            "coefficients" to listOf(0, -1, 0, -1, 5, -1, 0, -1, 0),
            "colors" to mapOf(
                "type" to "fixed",
                "minColor" to listOf(0, 0, 0),
                "maxColor" to listOf(255, 255, 255)
            )
        ))
        
        /** Blur filter */
        fun blur() = fromParameters(mapOf(
            "coefficients" to listOf(1, 1, 1, 1, 1, 1, 1, 1, 1).map { it / 9f },
            "colors" to mapOf(
                "type" to "fixed",
                "minColor" to listOf(0, 0, 0),
                "maxColor" to listOf(255, 255, 255)
            )
        ))
        
        /**
         * Test method to verify the convolution functionality
         */
        fun testConvolution() {
            // Test identity kernel
            val identityEffect = identity()
            assert(identityEffect.coefficients.contentEquals(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f))) {
                "Identity kernel should be [0,0,0,0,1,0,0,0,0]"
            }
            
            // Test emboss kernel
            val embossEffect = emboss()
            assert(embossEffect.coefficients.contentEquals(floatArrayOf(8f, 4f, 0f, 4f, 1f, -4f, 0f, -4f, -8f))) {
                "Emboss kernel should be [8,4,0,4,1,-4,0,-4,-8]"
            }
            
            // Test blur kernel
            val blurEffect = blur()
            val expectedBlur = floatArrayOf(1f/9f, 1f/9f, 1f/9f, 1f/9f, 1f/9f, 1f/9f, 1f/9f, 1f/9f, 1f/9f)
            assert(blurEffect.coefficients.contentEquals(expectedBlur)) {
                "Blur kernel should be all 1/9"
            }
            
            Log.i(EFFECT_NAME, "Convolution test passed - kernel coefficients are working correctly")
        }
    }
} 