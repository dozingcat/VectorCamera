package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.util.Log
import com.dozingcatsoftware.util.YuvUtils.yuvToRgb
import com.dozingcatsoftware.util.intFromArgbList
import com.dozingcatsoftware.vectorcamera.*
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

/**
 * StainedGlassEffect creates a mosaic-like appearance by segmenting the image into 
 * irregular regions and filling each region with its average color, similar to 
 * stained glass windows.
 */
class StainedGlassEffect private constructor(
    private val effectParams: Map<String, Any> = mapOf(),
    private val segmentSize: Int = 100,
    private val edgeThickness: Int = 2,
    private val edgeColor: Int = Color.BLACK,
    private val colorVariation: Float = 0.1f
) : Effect {

    override fun effectName() = EFFECT_NAME
    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()
        
        val width = cameraImage.width()
        val height = cameraImage.height()
        
        // Create bitmap using region-based segmentation (hybrid native/Kotlin)
        val bitmap = createStainedGlassBitmap(cameraImage, width, height, 0) // threads calculated internally
        
        val endTime = System.nanoTime()
        
        // Determine which implementation was used for metadata
        val (architecture, threadsUsed) = if (nativeLibraryLoaded) {
            Pair(CodeArchitecture.Native, calculateOptimalNativeThreads(height))
        } else {
            Pair(CodeArchitecture.Kotlin, calculateOptimalKotlinThreads(height))
        }
        
        val metadata = ProcessedBitmapMetadata(
            codeArchitecture = architecture,
            numThreads = threadsUsed,
            generationDurationNanos = endTime - startTime
        )
        
        return ProcessedBitmap(this, cameraImage, bitmap, metadata)
    }

    /**
     * Calculate optimal thread count for native processing based on image dimensions.
     */
    private fun calculateOptimalNativeThreads(height: Int): Int {
        return 1
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 32 // Minimum rows per thread to avoid overhead
        val maxThreads = minOf(numCores, height / minRowsPerThread, Effect.MAX_NATIVE_THREADS)
        return maxOf(1, maxThreads)
    }

    /**
     * Calculate optimal thread count for Kotlin processing based on image dimensions.
     */
    private fun calculateOptimalKotlinThreads(height: Int): Int {
        return 1
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 64 // Larger minimum for complex operations
        val maxThreads = minOf(numCores, height / minRowsPerThread, Effect.MAX_KOTLIN_THREADS)
        return maxOf(1, maxThreads)
    }

    /**
     * Create the stained glass effect using region-based segmentation.
     */
    private fun createStainedGlassBitmap(
        cameraImage: CameraImage, 
        width: Int, 
        height: Int, 
        numThreads: Int
    ): Bitmap {
        // Extract YUV data for processing
        val yData = cameraImage.getYBytes()
        val uData = cameraImage.getUBytes()
        val vData = cameraImage.getVBytes()
        
        val nativeThreads = calculateOptimalNativeThreads(height)
        val kotlinThreads = calculateOptimalKotlinThreads(height)
        
        // Try native implementation first for better performance
        val outputPixels = IntArray(width * height)
        
        val nativeSuccess = if (nativeLibraryLoaded) {
            Log.i(EFFECT_NAME, "Using native implementation with $nativeThreads threads")
            processStainedGlassNative(
                yData, uData, vData, width, height, segmentSize,
                edgeThickness, edgeColor, colorVariation, outputPixels, nativeThreads
            )
        } else {
            false
        }
        
        if (nativeSuccess) {
            // Native processing succeeded
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)
            return bitmap
        } else {
            // Fall back to Kotlin implementation
            Log.i(EFFECT_NAME, "Native failed, using Kotlin fallback with $kotlinThreads threads")
            return createStainedGlassBitmapKotlin(cameraImage, width, height, kotlinThreads)
        }
    }

    /**
     * Kotlin fallback implementation.
     */
    private fun createStainedGlassBitmapKotlin(
        cameraImage: CameraImage, 
        width: Int, 
        height: Int, 
        numThreads: Int
    ): Bitmap {
        // Extract YUV data for processing
        val yData = cameraImage.getYBytes()
        val uData = cameraImage.getUBytes()
        val vData = cameraImage.getVBytes()
        
        // Create segment map - each pixel gets assigned to a segment ID
        Log.i(EFFECT_NAME, "Creating segment map: $segmentSize")
        val segmentMap = createSegmentMap(width, height, segmentSize)
        
        // Calculate average color for each segment
        Log.i(EFFECT_NAME, "Calculating segment colors")
        val segmentColors = calculateSegmentColors(
            yData, uData, vData, width, height, segmentMap, numThreads
        )
        
        // Render final bitmap with segments and edges
        Log.i(EFFECT_NAME, "Rendering bitmap")
        return renderStainedGlass(width, height, segmentMap, segmentColors)
    }

    class SeedPoint(val x: Int, val y: Int, val segmentId: Int)

    /**
     * Create a segment map using a grid-based approach with random variation.
     * Each segment represents an irregular region that will be filled with a single color.
     */
    private fun createSegmentMap(width: Int, height: Int, avgSegmentSize: Int): Array<IntArray> {
        val segmentMap = Array(height) { IntArray(width) }
        
        // Create initial seed points in a rough grid pattern
        val seedPoints = mutableListOf<MutableList<SeedPoint>>()
        val gridSpacing = avgSegmentSize
        val variation = avgSegmentSize / 3

        Log.i(EFFECT_NAME, "gridSpacing: $gridSpacing")
        
        var segmentId = 0
        for (y in gridSpacing/2 until height step gridSpacing) {
            seedPoints.add(mutableListOf<SeedPoint>())
            val currentRow = seedPoints.last()
            for (x in gridSpacing/2 until width step gridSpacing) {
                // Add random variation to seed point positions
                val randomX = (x + Random.nextInt(-variation, variation)).coerceIn(0, width - 1)
                val randomY = (y + Random.nextInt(-variation, variation)).coerceIn(0, height - 1)
                currentRow.add(SeedPoint(randomX, randomY, segmentId))
                segmentId++
            }
        }

        Log.i(EFFECT_NAME, "seedPoints.size: ${seedPoints.size}")
        
        // Use a simplified Voronoi-like algorithm to assign pixels to nearest seed
        for (y in 0 until height) {
            if (y % 20 == 0) Log.i(EFFECT_NAME, "y=$y")
            val cellY = y / gridSpacing
            // We only need to check cells within two of the current pixel.
            // (This can be optimized further but is ok for now).
            val minCellY = (cellY - 2).coerceAtLeast(0)
            val maxCellY = (cellY + 2).coerceAtMost(seedPoints.size - 1)
            for (x in 0 until width) {
                val cellX = x / gridSpacing
                val minCellX = (cellX - 2).coerceAtLeast(0)
                val maxCellX = (cellX + 2).coerceAtMost(seedPoints[0].size - 1)
                var minDistance = Int.MAX_VALUE
                var nearestSegment = 0

                // Find nearest seed point
                for (cy in minCellY..maxCellY) {
                    for (cx in minCellX..maxCellX) {
                        val point = seedPoints[cy][cx]
                        val distanceSquared = (x - point.x) * (x - point.x) +
                                (y - point.y) * (y - point.y)
                        if (distanceSquared < minDistance) {
                            minDistance = distanceSquared
                            nearestSegment = point.segmentId
                        }
                    }
                }
                segmentMap[y][x] = nearestSegment
            }
        }
        
        return segmentMap
    }

    /**
     * Calculate the average color for each segment using YUV to RGB conversion.
     */
    private fun calculateSegmentColors(
        yData: ByteArray,
        uData: ByteArray, 
        vData: ByteArray,
        width: Int,
        height: Int,
        segmentMap: Array<IntArray>,
        numThreads: Int
    ): Map<Int, Int> {
        
        // Collect pixel data for each segment
        val segmentPixels = mutableMapOf<Int, MutableList<Int>>()
        
        if (numThreads == 1) {
            // Single-threaded processing
            processColorRows(0, height, width, height, yData, uData, vData, segmentMap, segmentPixels)
        } else {
            // Multi-threaded processing with synchronized map access
            val segmentPixelsSynced = java.util.concurrent.ConcurrentHashMap<Int, MutableList<Int>>()
            
            runBlocking {
                val jobs = mutableListOf<Job>()
                val rowsPerThread = height / numThreads
                
                for (threadIndex in 0 until numThreads) {
                    val startY = threadIndex * rowsPerThread
                    val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                    
                    val job = launch(Dispatchers.Default) {
                        val localSegmentPixels = mutableMapOf<Int, MutableList<Int>>()
                        processColorRows(startY, endY, width, height, yData, uData, vData, segmentMap, localSegmentPixels)
                        
                        // Merge results
                        synchronized(segmentPixelsSynced) {
                            for ((segmentId, pixels) in localSegmentPixels) {
                                segmentPixelsSynced.computeIfAbsent(segmentId) { mutableListOf() }.addAll(pixels)
                            }
                        }
                    }
                    jobs.add(job)
                }
                
                jobs.forEach { it.join() }
            }
            
            segmentPixels.putAll(segmentPixelsSynced)
        }
        
        // Calculate average color for each segment
        val segmentColors = mutableMapOf<Int, Int>()
        for ((segmentId, pixels) in segmentPixels) {
            if (pixels.isNotEmpty()) {
                var totalRed = 0L
                var totalGreen = 0L
                var totalBlue = 0L

                for (rgb in pixels) {
                    totalRed += (rgb shr 16)
                    totalGreen += (rgb shr 8) and 0xFF
                    totalBlue += rgb and 0xFF
                }
                val avgR = (totalRed / pixels.size).toInt()
                val avgG = (totalGreen / pixels.size).toInt()
                val avgB = (totalBlue / pixels.size).toInt()
                
                // Add slight color variation for more interesting appearance
                val variation = (colorVariation * 128).toInt()
                val finalR = (avgR + Random.nextInt(-variation, variation)).coerceIn(0, 255)
                val finalG = (avgG + Random.nextInt(-variation, variation)).coerceIn(0, 255)
                val finalB = (avgB + Random.nextInt(-variation, variation)).coerceIn(0, 255)
                
                segmentColors[segmentId] = Color.rgb(finalR, finalG, finalB)
            }
        }
        
        return segmentColors
    }

    /**
     * Process a range of rows for color calculation.
     */
    private fun processColorRows(
        startY: Int,
        endY: Int, 
        width: Int,
        height: Int,
        yData: ByteArray,
        uData: ByteArray,
        vData: ByteArray,
        segmentMap: Array<IntArray>,
        segmentPixels: MutableMap<Int, MutableList<Int>>
    ) {
        for (y in startY until endY) {
            for (x in 0 until width) {
                val segmentId = segmentMap[y][x]
                
                // Get YUV values
                val yVal = yData[y * width + x].toInt() and 0xFF
                val uVal = uData[(y / 2) * (width / 2) + (x / 2)].toInt() and 0xFF
                val vVal = vData[(y / 2) * (width / 2) + (x / 2)].toInt() and 0xFF
                
                // Convert YUV to RGB
                val rgb = yuvToRgb(yVal, uVal, vVal)

                // Add to segment's pixel collection
                segmentPixels.computeIfAbsent(segmentId) { mutableListOf() }.add(rgb)
            }
        }
    }

    /**
     * Render the final stained glass bitmap with segments and edge outlines.
     */
    private fun renderStainedGlass(
        width: Int, 
        height: Int, 
        segmentMap: Array<IntArray>, 
        segmentColors: Map<Int, Int>
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Fill segments with their average colors
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val segmentId = segmentMap[y][x]
                pixels[y * width + x] = segmentColors[segmentId] ?: Color.BLACK
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        
        // Draw edges between segments
        if (edgeThickness > 0) {
            val edgePaint = Paint().apply {
                color = edgeColor
                strokeWidth = edgeThickness.toFloat()
                isAntiAlias = true
            }
            
            // Find and draw segment boundaries
            for (y in 0 until height - 1) {
                for (x in 0 until width - 1) {
                    val currentSegment = segmentMap[y][x]
                    
                    // Check right neighbor
                    if (segmentMap[y][x + 1] != currentSegment) {
                        canvas.drawLine(
                            (x + 1).toFloat(), y.toFloat(),
                            (x + 1).toFloat(), (y + 1).toFloat(),
                            edgePaint
                        )
                    }
                    
                    // Check bottom neighbor
                    if (segmentMap[y + 1][x] != currentSegment) {
                        canvas.drawLine(
                            x.toFloat(), (y + 1).toFloat(),
                            (x + 1).toFloat(), (y + 1).toFloat(),
                            edgePaint
                        )
                    }
                }
            }
        }

        return bitmap
    }

    companion object {
        const val EFFECT_NAME = "stained_glass"
        
        // Native library loading
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
        
        /**
         * Native method for stained glass effect processing.
         */
        private external fun processStainedGlassNative(
            yData: ByteArray,
            uData: ByteArray,
            vData: ByteArray,
            width: Int,
            height: Int,
            segmentSize: Int,
            edgeThickness: Int,
            edgeColor: Int,
            colorVariation: Float,
            outputPixels: IntArray,
            numThreads: Int
        ): Boolean
        
        fun fromParameters(params: Map<String, Any>): StainedGlassEffect {
            val segmentSize = (params.getOrElse("segmentSize", { 200 }) as Number).toInt()
            Log.i(EFFECT_NAME, "fromParameters: $params segmentSize: $segmentSize")
            val edgeThickness = (params.getOrElse("edgeThickness", { 2 }) as Number).toInt()
            val edgeColor = if (params.containsKey("edgeColor")) {
                intFromArgbList(params["edgeColor"] as List<Int>)
            } else {
                Color.BLACK
            }
            val colorVariation = (params.getOrElse("colorVariation", { 0.1 }) as Number).toFloat()
            
            return StainedGlassEffect(params, segmentSize, edgeThickness, edgeColor, colorVariation)
        }
        
        // Factory methods for different configurations
        fun defaultStainedGlass() = fromParameters(mapOf(
            "segmentSize" to 100,
            "edgeThickness" to 2,
            "edgeColor" to listOf(0, 0, 0),
            "colorVariation" to 0.1
        ))
        
        fun largeSegments() = fromParameters(mapOf(
            "segmentSize" to 200,
            "edgeThickness" to 3,
            "edgeColor" to listOf(64, 64, 64),
            "colorVariation" to 0.15
        ))
        
        fun fineDetail() = fromParameters(mapOf(
            "segmentSize" to 50,
            "edgeThickness" to 1,
            "edgeColor" to listOf(0, 0, 0),
            "colorVariation" to 0.05
        ))
    }
}
