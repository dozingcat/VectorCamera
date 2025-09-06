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
    private val sectionsPerRow: Int = 64,
    private val edgeThickness: Double = 0.002,
    private val edgeColor: Int = Color.BLACK,
    private val colorVariation: Float = 0.1f
) : Effect {

    // Cache for segment map to avoid recomputing every frame
    private data class SegmentMapCache(
        val width: Int,
        val height: Int,
        val segmentSize: Int,
        val segmentMap: Array<IntArray>,
        val seedPoints: List<List<SeedPoint>>
    )
    
    @Volatile
    private var cachedSegmentMap: SegmentMapCache? = null

    override fun effectName() = EFFECT_NAME
    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()

        // Enforce minimum segment size, and if we're scaling down for display the minimum size
        // needs to be larger so that when scaled down it will be normal.
        val segmentScale = if (cameraImage.displaySize.width == 0) 1.0 else (cameraImage.width().toDouble() / cameraImage.displaySize.width).coerceAtLeast(1.0)
        val minSegmentSize = (16 * segmentScale).roundToInt()
        val segmentSize = (cameraImage.width() / sectionsPerRow).coerceAtLeast(minSegmentSize)

        // Create bitmap using region-based segmentation (hybrid native/Kotlin)
        val bitmap = createStainedGlassBitmap(cameraImage, segmentSize)

        val endTime = System.nanoTime()
        
        // Determine which implementation was used for metadata
        val architecture = if (nativeLibraryLoaded) CodeArchitecture.Native else CodeArchitecture.Kotlin
        
        val metadata = ProcessedBitmapMetadata(
            codeArchitecture = architecture,
            numThreads = 1, // Single-threaded for simplicity
            generationDurationNanos = endTime - startTime
        )
        
        return ProcessedBitmap(this, cameraImage, bitmap, metadata)
    }

    /**
     * Create the stained glass effect using region-based segmentation.
     */
    private fun createStainedGlassBitmap(
        cameraImage: CameraImage,
        segmentSize: Int,
    ): Bitmap {
        val width = cameraImage.width()
        val height = cameraImage.height()
        val yData = cameraImage.getYBytes()
        val uData = cameraImage.getUBytes()
        val vData = cameraImage.getVBytes()
        
        // Try native implementation first for better performance
        val outputPixels = IntArray(width * height)
        val thicknessPixels = (edgeThickness * width).roundToInt().coerceAtLeast(1)
        
        val nativeSuccess = if (nativeLibraryLoaded) {
            processStainedGlassNative(
                yData, uData, vData, width, height, segmentSize,
                thicknessPixels, edgeColor, colorVariation, outputPixels, 1
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
            Log.i(EFFECT_NAME, "Native failed, using Kotlin fallback")
            return createStainedGlassBitmapKotlin(cameraImage, width, height, segmentSize)
        }
    }

    /**
     * Kotlin fallback implementation.
     */
    private fun createStainedGlassBitmapKotlin(
        cameraImage: CameraImage, 
        width: Int, 
        height: Int,
        segmentSize: Int,
    ): Bitmap {
        // Extract YUV data for processing
        val yData = cameraImage.getYBytes()
        val uData = cameraImage.getUBytes()
        val vData = cameraImage.getVBytes()
        
        // Get or create cached segment map
        val (segmentMap, seedPoints) = getCachedSegmentMap(width, height, segmentSize)
        
        // Calculate average color for each segment
        val segmentColors = calculateSegmentColors(
            yData, uData, vData, width, height, segmentMap
        )
        
        // Render final bitmap with segments and edges
        return renderStainedGlass(width, height, segmentMap, segmentColors)
    }

    class SeedPoint(val x: Int, val y: Int, val segmentId: Int)

    /**
     * Get cached segment map or create new one if cache is invalid.
     */
    private fun getCachedSegmentMap(width: Int, height: Int, segmentSize: Int): Pair<Array<IntArray>, List<List<SeedPoint>>> {
        val cache = cachedSegmentMap
        
        // Check if cache is valid
        if (cache != null && cache.width == width && cache.height == height && cache.segmentSize == segmentSize) {
            return Pair(cache.segmentMap, cache.seedPoints)
        }
        
        // Cache is invalid, create new segment map
        Log.i(EFFECT_NAME, "Creating new segment map for ${width}x${height}, sectionsPerRow=$sectionsPerRow")
        val seedPoints = createSeedPoints(width, height, segmentSize)
        val segmentMap = createSegmentMapFromSeeds(width, height, segmentSize, seedPoints)
        
        // Update cache
        cachedSegmentMap = SegmentMapCache(width, height, sectionsPerRow, segmentMap, seedPoints)
        
        return Pair(segmentMap, seedPoints)
    }

    /**
     * Create seed points in a grid pattern with random variation.
     */
    private fun createSeedPoints(width: Int, height: Int, segmentSize: Int): List<List<SeedPoint>> {
        val seedPoints = mutableListOf<MutableList<SeedPoint>>()
        val gridSpacing = segmentSize
        val variation = segmentSize / 3

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

        return seedPoints
    }

    /**
     * Create segment map from seed points using optimized Voronoi-like algorithm.
     */
    private fun createSegmentMapFromSeeds(width: Int, height: Int, segmentSize: Int, seedPoints: List<List<SeedPoint>>): Array<IntArray> {
        val segmentMap = Array(height) { IntArray(width) }
        val gridSpacing = segmentSize
        
        // Use a simplified Voronoi-like algorithm to assign pixels to nearest seed
        for (y in 0 until height) {
            val cellY = y / gridSpacing
            // Only need to check 3x3 neighborhood - mathematically sufficient
            val minCellY = (cellY - 1).coerceAtLeast(0)
            val maxCellY = (cellY + 1).coerceAtMost(seedPoints.size - 1)
            for (x in 0 until width) {
                val cellX = x / gridSpacing
                val minCellX = (cellX - 1).coerceAtLeast(0)
                val maxCellX = (cellX + 1).coerceAtMost(seedPoints[0].size - 1)
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
        segmentMap: Array<IntArray>
    ): Map<Int, Int> {
        
        // Collect pixel data for each segment
        val segmentPixels = mutableMapOf<Int, MutableList<Int>>()
        
        // Single-threaded processing for simplicity
        processColorRows(0, height, width, height, yData, uData, vData, segmentMap, segmentPixels)
        
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

        val thicknessPixels = (edgeThickness * width).roundToInt().coerceAtLeast(1)
        
        // Draw edges between segments
        if (thicknessPixels > 0) {
            val edgePaint = Paint().apply {
                color = edgeColor
                strokeWidth = thicknessPixels.toFloat()
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
            val sectionsPerRow = (params.getOrElse("sectionsPerRow", { 64 }) as Number).toInt()
            // edgeThickness is fraction of image width.
            val edgeThickness = (params.getOrElse("edgeThickness", { 0.002 }) as Number).toDouble()
            val edgeColor = if (params.containsKey("edgeColor")) {
                intFromArgbList(params["edgeColor"] as List<Int>)
            } else {
                Color.BLACK
            }
            val colorVariation = (params.getOrElse("colorVariation", { 0.1 }) as Number).toFloat()
            
            return StainedGlassEffect(params, sectionsPerRow, edgeThickness, edgeColor, colorVariation)
        }
        
        // Factory methods for different configurations
        fun defaultStainedGlass() = fromParameters(mapOf(
            "sectionsPerRow" to 64,
            "edgeThickness" to 0.002,
            "edgeColor" to listOf(0, 0, 0),
            "colorVariation" to 0.1
        ))
        
        fun largeSegments() = fromParameters(mapOf(
            "sectionsPerRow" to 32,
            "edgeThickness" to 0.003,
            "edgeColor" to listOf(64, 64, 64),
            "colorVariation" to 0.15
        ))
        
        fun fineDetail() = fromParameters(mapOf(
            "sectionsPerRow" to 128,
            "edgeThickness" to 0.001,
            "edgeColor" to listOf(0, 0, 0),
            "colorVariation" to 0.05
        ))
    }
}
