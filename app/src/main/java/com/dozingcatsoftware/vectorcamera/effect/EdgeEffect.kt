package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Pure Kotlin implementation of EdgeEffect that maps edge strength to colors.
 * Supports advanced color mapping including gradients.
 */
class EdgeEffect private constructor(
    private val effectParams: Map<String, Any> = mapOf(),
    private val colorMap: IntArray? = null,
    private val alphaMap: IntArray? = null,
    private val backgroundFn: ((CameraImage, Canvas, RectF) -> Unit)? = null
) : Effect {

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        backgroundFn?.invoke(cameraImage, canvas, rect)
    }

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()
        
        val width = cameraImage.width()
        val height = cameraImage.height()
        val multiplier = minOf(4, maxOf(2, Math.round(width / 480f)))

        // Get YUV data directly from CameraImage
        val yBytes = cameraImage.getYBytes()
        val (bitmap, threadsUsed, architectureUsed) = createBitmapFromYBytes(yBytes, width, height, multiplier)
        
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

    private fun createBitmapFromYBytes(yData: ByteArray, width: Int, height: Int, multiplier: Int): Triple<Bitmap, Int, CodeArchitecture> {
        val pixels = IntArray(width * height)
        val nativeThreads = calculateOptimalNativeThreads(height)
        val kotlinThreads = calculateOptimalKotlinThreads(height)

        val t1 = System.currentTimeMillis()
        
        val actualThreads: Int
        val architecture: CodeArchitecture

        val lookupMap = colorMap ?: alphaMap!!
        if (nativeLibraryLoaded) {
            // Use optimized native implementation for fixed color maps only
            actualThreads = nativeThreads
            architecture = CodeArchitecture.Native
            processImageNativeFromYuvBytes(yData, width, height, multiplier, lookupMap, pixels, actualThreads)
        } else {
            // Fallback to Kotlin implementation with coroutines
            actualThreads = kotlinThreads
            architecture = CodeArchitecture.Kotlin
            if (actualThreads == 1) {
                processRows(0, height, width, height, multiplier, yData, pixels, lookupMap)
            } else {
                runBlocking {
                    val jobs = mutableListOf<Job>()
                    val rowsPerThread = height / actualThreads
                    
                    for (threadIndex in 0 until actualThreads) {
                        val startY = threadIndex * rowsPerThread
                        val endY = if (threadIndex == actualThreads - 1) height else (threadIndex + 1) * rowsPerThread
                        
                        val job = launch(Dispatchers.Default) {
                            processRows(startY, endY, width, height, multiplier, yData, pixels, lookupMap)
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
            val impl = if (architecture == CodeArchitecture.Native) "native" else "Kotlin"
            Log.i(EFFECT_NAME, "Generated ${width}x${height} image in $elapsed ms with $actualThreads threads ($impl)")
        }
        return Triple(bitmap, actualThreads, architecture)
    }

    private fun processRows(
        startY: Int, 
        endY: Int, 
        width: Int, 
        height: Int, 
        multiplier: Int,
        yData: ByteArray,
        pixels: IntArray,
        lookupMap: IntArray
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
                val color = lookupMap[edgeStrength]
                pixels[pixelIndex] = color
            }
        }
    }

    companion object {
        const val EFFECT_NAME = "edge"
        
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
        
        fun fromParameters(effectParams: Map<String, Any>): EdgeEffect {
            // Parse color scheme parameters (backwards compatibility)
            val colorParams = effectParams.getOrElse("colors", { effectParams }) as Map<String, Any>
            
            when (colorParams["type"]) {
                "fixed" -> {
                    val minColor = parseColorFromList(colorParams, "minColor", "minEdgeColor")
                    val maxColor = parseColorFromList(colorParams, "maxColor", "maxEdgeColor")
                    val colorMap = createFixedColorMap(minColor, maxColor)
                    return EdgeEffect(effectParams, colorMap = colorMap)
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
                    return EdgeEffect(effectParams, alphaMap = alphaMap, backgroundFn = backgroundFn)
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
                    return EdgeEffect(effectParams, alphaMap = alphaMap, backgroundFn = backgroundFn)
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
                    return EdgeEffect(effectParams, alphaMap = alphaMap, backgroundFn = backgroundFn)
                }
                else -> {
                    // Default to black to white gradient
                    val colorMap = createFixedColorMap(Color.BLACK, Color.WHITE)
                    return EdgeEffect(effectParams, colorMap = colorMap)
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
        
        // Factory methods for common configurations
        fun blackToWhite() = fromParameters(mapOf(
            "colors" to mapOf(
                "type" to "fixed",
                "minColor" to listOf(0, 0, 0),
                "maxColor" to listOf(255, 255, 255)
            )
        ))
        
        fun whiteToRed() = fromParameters(mapOf(
            "colors" to mapOf(
                "type" to "fixed",
                "minColor" to listOf(255, 255, 255),
                "maxColor" to listOf(255, 0, 0)
            )
        ))
    }
} 