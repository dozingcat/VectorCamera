package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.dozingcatsoftware.vectorcamera.CameraImage
import com.dozingcatsoftware.vectorcamera.CodeArchitecture
import com.dozingcatsoftware.vectorcamera.ProcessedBitmap
import com.dozingcatsoftware.vectorcamera.ProcessedBitmapMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Topographic map look: brightness is smoothed and quantized into a few levels, each level is
 * filled with a color from a palette, and boundaries between levels are drawn as contour lines.
 */
class ContourEffect private constructor(
        private val effectParams: Map<String, Any>,
        private val levels: Int,
        private val smoothingRadius: Int,
        private val lineThickness: Int,
        private val fillColors: IntArray,
        private val lineColor: Int) : Effect {

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()
        val width = cameraImage.width()
        val height = cameraImage.height()
        val yBytes = cameraImage.getYBytes()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        var architecture = CodeArchitecture.Kotlin
        // The native code hands out row chunks dynamically, so slow cores just take fewer and
        // any thread count works. Three gets most of the speedup without occupying every core.
        var numThreads = calculateOptimalThreads(height, MAX_NATIVE_THREADS)
        var done = false
        if (nativeLibraryLoaded) {
            try {
                // Renders straight into the bitmap, skipping the pixel array and setPixels copy.
                done = processImageNativeToBitmap(yBytes, width, height, levels, smoothingRadius,
                                                  lineThickness, fillColors, lineColor, bitmap,
                                                  numThreads)
                if (done) {
                    architecture = CodeArchitecture.Native
                }
            } catch (e: Exception) {
                Log.w(EFFECT_NAME, "Native processing failed, falling back to Kotlin: ${e.message}")
            }
        }
        if (!done) {
            numThreads = calculateOptimalThreads(height, Effect.MAX_KOTLIN_THREADS)
            val pixels = IntArray(width * height)
            renderKotlin(yBytes, width, height, numThreads, pixels)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        val metadata = ProcessedBitmapMetadata(
                codeArchitecture = architecture,
                numThreads = numThreads,
                generationDurationNanos = System.nanoTime() - startTime)
        return ProcessedBitmap(this, cameraImage, bitmap, metadata)
    }

    private fun renderKotlin(yBytes: ByteArray, width: Int, height: Int, numThreads: Int,
                             pixels: IntArray) {
        val levelPlane = computeLevels(yBytes, width, height, levels, smoothingRadius)
        if (numThreads == 1) {
            renderLevels(levelPlane, width, height, 0, height, fillColors, lineColor,
                         lineThickness, pixels)
        } else {
            runBlocking {
                val jobs = mutableListOf<Job>()
                val rowsPerThread = height / numThreads
                for (threadIndex in 0 until numThreads) {
                    val startY = threadIndex * rowsPerThread
                    val endY = if (threadIndex == numThreads - 1) height else (threadIndex + 1) * rowsPerThread
                    jobs.add(launch(Dispatchers.Default) {
                        renderLevels(levelPlane, width, height, startY, endY, fillColors,
                                     lineColor, lineThickness, pixels)
                    })
                }
                jobs.forEach { it.join() }
            }
        }
    }

    private fun calculateOptimalThreads(height: Int, maxThreads: Int): Int {
        val numCores = Runtime.getRuntime().availableProcessors()
        val minRowsPerThread = 32
        return maxOf(1, minOf(numCores, height / minRowsPerThread, maxThreads))
    }

    companion object {
        const val EFFECT_NAME = "contour"
        const val DEFAULT_LEVELS = 8
        const val DEFAULT_SMOOTHING = 2
        const val DEFAULT_LINE_THICKNESS = 2
        const val MAX_NATIVE_THREADS = 3

        /**
         * Native implementation of computeLevels + renderLevels (see contour_effect_native.cpp)
         * rendering into `bitmap`, which must be ARGB_8888 and width x height. `fillColors`
         * must have at least `levels` entries. Returns false without modifying the bitmap if
         * it can't be used directly.
         */
        private external fun processImageNativeToBitmap(
            yData: ByteArray,
            width: Int,
            height: Int,
            levels: Int,
            smoothingRadius: Int,
            lineThickness: Int,
            fillColors: IntArray,
            lineColor: Int,
            bitmap: Bitmap,
            numThreads: Int
        ): Boolean

        private var nativeLibraryLoaded = false

        init {
            nativeLibraryLoaded = Effect.loadNativeLibrary()
        }

        /**
         * Quantizes brightness into `levels` bands (0 to levels-1) after a box blur of radius
         * `smoothingRadius`, which keeps sensor noise from producing speckled boundaries.
         */
        fun computeLevels(yData: ByteArray, width: Int, height: Int, levels: Int,
                          smoothingRadius: Int): ByteArray {
            val smoothed = if (smoothingRadius > 0) boxBlur(yData, width, height, smoothingRadius) else yData
            val out = ByteArray(width * height)
            for (i in out.indices) {
                out[i] = ((smoothed[i].toInt() and 0xFF) * levels / 256).toByte()
            }
            return out
        }

        /**
         * Fills rows [startY, endY) of `pixels` with the level colors, drawing `lineColor` where
         * a pixel's level differs from a neighbor's. Thickness 1 compares only the left and top
         * neighbors, giving one-pixel lines; thickness 2 compares all four, giving two-pixel lines.
         */
        fun renderLevels(levelPlane: ByteArray, width: Int, height: Int, startY: Int, endY: Int,
                         fillColors: IntArray, lineColor: Int, lineThickness: Int,
                         pixels: IntArray) {
            for (y in startY until endY) {
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    val level = levelPlane[i]
                    var isLine = (x > 0 && levelPlane[i - 1] != level) ||
                                 (y > 0 && levelPlane[i - width] != level)
                    if (!isLine && lineThickness > 1) {
                        isLine = (x < width - 1 && levelPlane[i + 1] != level) ||
                                 (y < height - 1 && levelPlane[i + width] != level)
                    }
                    pixels[i] = if (isLine) lineColor else fillColors[level.toInt()]
                }
            }
        }

        /** Separable box blur with running sums; edges are clamped. */
        fun boxBlur(data: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
            val kernelSize = 2 * radius + 1
            val temp = ByteArray(width * height)
            for (y in 0 until height) {
                val row = y * width
                var total = 0
                for (k in -radius..radius) {
                    total += data[row + k.coerceIn(0, width - 1)].toInt() and 0xFF
                }
                for (x in 0 until width) {
                    temp[row + x] = (total / kernelSize).toByte()
                    total += (data[row + (x + radius + 1).coerceIn(0, width - 1)].toInt() and 0xFF) -
                             (data[row + (x - radius).coerceIn(0, width - 1)].toInt() and 0xFF)
                }
            }
            val out = ByteArray(width * height)
            val columnTotals = IntArray(width)
            for (k in -radius..radius) {
                val row = k.coerceIn(0, height - 1) * width
                for (x in 0 until width) {
                    columnTotals[x] += temp[row + x].toInt() and 0xFF
                }
            }
            for (y in 0 until height) {
                val row = y * width
                val addRow = (y + radius + 1).coerceIn(0, height - 1) * width
                val subRow = (y - radius).coerceIn(0, height - 1) * width
                for (x in 0 until width) {
                    out[row + x] = (columnTotals[x] / kernelSize).toByte()
                    columnTotals[x] += (temp[addRow + x].toInt() and 0xFF) - (temp[subRow + x].toInt() and 0xFF)
                }
            }
            return out
        }

        fun fromParameters(params: Map<String, Any>): ContourEffect {
            val levels = (params.getOrElse("levels", {DEFAULT_LEVELS}) as Number).toInt().coerceIn(2, 256)
            // The native implementation supports radii up to 127.
            val smoothing = (params.getOrElse("smoothing", {DEFAULT_SMOOTHING}) as Number).toInt().coerceIn(0, 127)
            val thickness = (params.getOrElse("lineThickness", {DEFAULT_LINE_THICKNESS}) as Number).toInt()
            val colors = params.getOrElse("colors", {mapOf<String, Any>()}) as Map<String, Any>
            val fillStops = (colors.getOrElse("fill", {listOf(listOf(0, 0, 0), listOf(255, 255, 255))})
                    as List<List<Int>>).map {Color.argb(255, it[0], it[1], it[2])}
            val line = colors.getOrElse("line", {listOf(0, 0, 0)}) as List<Int>
            val lineColor = Color.argb(255, line[0], line[1], line[2])
            // One fill color per level, spread evenly through the palette.
            val palette = ColorMapEffect.createPaletteColorMap(fillStops)
            val fillColors = IntArray(levels) {palette[it * 255 / (levels - 1)]}
            return ContourEffect(params, levels, smoothing, thickness, fillColors, lineColor)
        }
    }
}
