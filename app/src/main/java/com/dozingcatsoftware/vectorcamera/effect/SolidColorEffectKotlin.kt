package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Pure Kotlin implementation of SolidColorEffect that maps each pixel to an output color based on its brightness.
 */
class SolidColorEffectKotlin(
    private val effectParams: Map<String, Any> = mapOf(),
    private val colorMap: IntArray,
    private val backgroundFn: (CameraImage, Canvas, RectF) -> Unit = { _, _, _ -> }
) : Effect {

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        backgroundFn.invoke(cameraImage, canvas, rect)
    }

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val width = cameraImage.width()
        val height = cameraImage.height()

        // Get YUV data directly from CameraImage
        val yuvBytes = cameraImage.getYuvBytes()!!
        return createBitmapFromYuvBytes(yuvBytes, width, height)
    }

    var numFrames: Int = 0

    private fun createBitmapFromYuvBytes(yuvBytes: ByteArray, width: Int, height: Int): Bitmap {
        // Determine optimal number of threads based on CPU cores and image size
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 32 // Minimum rows per thread to avoid overhead
        val maxThreads = minOf(numCores, height / minRowsPerThread)
        val numThreads = maxOf(1, maxThreads)

        val t1 = System.currentTimeMillis()
        
        val ySize = width * height
        // Extract Y plane from the flattened YUV bytes (we only need luminance for solid color effect)
        val yData = yuvBytes.sliceArray(0 until ySize)

        val pixels = IntArray(width * height)

        // Multi-threaded processing
        if (numThreads == 1) {
            processRows(0, height, width, yData, pixels, colorMap)
        } else {
            runBlocking {
                val jobs = mutableListOf<Job>()
                val rowsPerThread = height / numThreads
                
                for (threadIndex in 0 until numThreads) {
                    val startY = threadIndex * rowsPerThread
                    val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                    
                    val job = launch(Dispatchers.Default) {
                        processRows(startY, endY, width, yData, pixels, colorMap)
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
        yData: ByteArray,
        pixels: IntArray,
        colorMap: IntArray
    ) {
        for (y in startY until endY) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x

                // Get Y (luminance) value and use it as index into color map
                val yValue = yData[pixelIndex].toInt() and 0xFF
                
                // Map brightness to color using lookup table
                pixels[pixelIndex] = colorMap[yValue]
            }
        }
    }

    companion object {
        const val EFFECT_NAME = "solid_color_kotlin"
        
        fun fromParameters(effectParams: Map<String, Any>): SolidColorEffectKotlin {
            // Parse color scheme parameters (backwards compatibility)
            val colorParams = effectParams.getOrElse("colors", { effectParams }) as Map<String, Any>
            
            when (colorParams["type"]) {
                "fixed" -> {
                    val minColor = parseColorFromList(colorParams, "minColor", "minEdgeColor")
                    val maxColor = parseColorFromList(colorParams, "maxColor", "maxEdgeColor")
                    val colorMap = createFixedColorMap(minColor, maxColor)
                    return SolidColorEffectKotlin(effectParams, colorMap)
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
                    return SolidColorEffectKotlin(effectParams, alphaMap, backgroundFn)
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
                    return SolidColorEffectKotlin(effectParams, alphaMap, backgroundFn)
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
                    return SolidColorEffectKotlin(effectParams, alphaMap, backgroundFn)
                }
                else -> {
                    // Default to black to white gradient
                    val colorMap = createFixedColorMap(Color.BLACK, Color.WHITE)
                    return SolidColorEffectKotlin(effectParams, colorMap)
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
        
        fun whiteToBlack() = fromParameters(mapOf(
            "colors" to mapOf(
                "type" to "fixed",
                "minColor" to listOf(255, 255, 255),
                "maxColor" to listOf(0, 0, 0)
            )
        ))
        
        /**
         * Test method to verify the color mapping functionality
         */
        fun testColorMap() {
            val effect = blackToWhite()
            val colorMap = effect.colorMap
            
            // Test key values
            assert(colorMap[0] == Color.BLACK) { "colorMap[0] should be black, got ${colorMap[0]}" }
            assert(colorMap[255] == Color.WHITE) { "colorMap[255] should be white, got ${colorMap[255]}" }
            assert(Color.red(colorMap[128]) == 128) { "colorMap[128] red should be ~128, got ${Color.red(colorMap[128])}" }
            assert(Color.green(colorMap[128]) == 128) { "colorMap[128] green should be ~128, got ${Color.green(colorMap[128])}" }
            assert(Color.blue(colorMap[128]) == 128) { "colorMap[128] blue should be ~128, got ${Color.blue(colorMap[128])}" }
            
            Log.i(EFFECT_NAME, "Color map test passed - brightness to color mapping is working correctly")
        }
    }
} 