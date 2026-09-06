package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.dozingcatsoftware.util.YuvUtils
import com.dozingcatsoftware.util.intFromArgbList
import com.dozingcatsoftware.vectorcamera.CameraImage
import com.dozingcatsoftware.vectorcamera.CodeArchitecture
import com.dozingcatsoftware.vectorcamera.ProcessedBitmap
import com.dozingcatsoftware.vectorcamera.ProcessedBitmapMetadata
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stippling effect: the image is drawn as dots placed by Poisson disk sampling, with the local
 * dot density following the local tone of the image.
 *
 * Dot positions come from a "progressive" Poisson disk point set generated once per image size
 * and cached: Bridson's algorithm is run in several layers of increasing density, each keeping
 * the points of the previous layers, and each point gets a rank in (0, 1] such that the points
 * with rank <= t are approximately Poisson disk distributed with density t times the maximum.
 * Each frame then draws a point if its rank is <= the local darkness. Because the positions are
 * fixed, the output is stable over time instead of flickering.
 *
 * See stipple_effect.h for the native implementation; the Kotlin code here mirrors it.
 */
class StippleEffect private constructor(
    private val effectParams: Map<String, Any>,
    // Approximate number of dots across the image width at maximum density.
    private val dotsAcross: Int,
    // Number of density-doubling layers; the lightest tone that gets any dots is 2^-(layers-1).
    private val numLayers: Int,
    // Base dot radius as a fraction of the minimum dot spacing.
    private val dotRadiusFraction: Double,
    // 0 = constant dot size, 1 = radius varies from 0.5x to 1.5x between light and dark.
    private val dotSizeVariation: Double,
    // Exponent applied to darkness; < 1 makes the image darker, > 1 lighter.
    private val gamma: Double,
    private val colorFromImage: Boolean,
    private val dotColor: Int,
    private val backgroundColor: Int,
    // If true, dots are dense where the image is bright (for dark backgrounds).
    private val invertTone: Boolean,
    // In colorFromImage mode, luminance and chroma adjustments applied to the dot colors.
    private val dotLuminanceScale: Double,
    private val chromaBoost: Double,
    private val seed: Int,
) : Effect {

    override fun effectName() = EFFECT_NAME
    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()
        val width = cameraImage.width()
        val height = cameraImage.height()

        // If the image is larger than the display, keep the spacing large enough that the dots
        // are still distinct after it's scaled down.
        val displayScale = if (cameraImage.displaySize.width == 0) 1.0
                else max(1.0, width.toDouble() / cameraImage.displaySize.width)
        val minSpacing = max(width.toDouble() / dotsAcross, MIN_SPACING_PIXELS * displayScale)
                .toFloat()
        val dotRadius = (minSpacing * dotRadiusFraction).toFloat()
        val toneCellSize = max(1, minSpacing.roundToInt())

        val points = pointsForSize(width, height, minSpacing, numLayers, seed)
        val pixels = IntArray(width * height)

        var architecture = CodeArchitecture.Kotlin
        if (nativeLibraryLoaded) {
            val ok = renderStippleNative(
                cameraImage.getYBytes(), cameraImage.getUBytes(), cameraImage.getVBytes(),
                width, height, points, dotRadius, dotSizeVariation.toFloat(), gamma.toFloat(),
                toneCellSize, backgroundColor, dotColor, colorFromImage,
                dotLuminanceScale.toFloat(), chromaBoost.toFloat(), invertTone, pixels)
            if (ok) {
                architecture = CodeArchitecture.Native
            } else {
                Log.w(EFFECT_NAME, "Native rendering failed, using Kotlin")
            }
        }
        if (architecture == CodeArchitecture.Kotlin) {
            renderStippleKotlin(
                cameraImage.getYBytes(), cameraImage.getUBytes(), cameraImage.getVBytes(),
                width, height, points, dotRadius, toneCellSize, pixels)
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val metadata = ProcessedBitmapMetadata(
            codeArchitecture = architecture,
            numThreads = 1,
            generationDurationNanos = System.nanoTime() - startTime)
        return ProcessedBitmap(this, cameraImage, bitmap, metadata)
    }

    /**
     * Kotlin implementation of rendering; mirrors Stipple::renderStipple in stipple_effect.h.
     * `points` holds (x, y, rank) triples.
     */
    private fun renderStippleKotlin(
        yData: ByteArray, uData: ByteArray, vData: ByteArray,
        width: Int, height: Int, points: FloatArray,
        dotRadius: Float, toneCellSize: Int, out: IntArray,
    ) {
        out.fill(backgroundColor or (0xFF shl 24))
        val tone = ToneMap(yData, uData, vData, width, height, toneCellSize, colorFromImage)
        val masks = mutableMapOf<Int, DotMask>()
        val radiusStep = 0.25f
        val applyGamma = Math.abs(gamma - 1.0) > 1e-4
        val numPoints = points.size / 3

        for (i in 0 until numPoints) {
            val px = points[3 * i]
            val py = points[3 * i + 1]
            val rank = points[3 * i + 2]
            val yValue = tone.sample(tone.yAvg, px, py)
            var darkness = if (invertTone) yValue / 255f else 1f - yValue / 255f
            darkness = darkness.coerceIn(0f, 1f)
            if (applyGamma) {
                darkness = darkness.toDouble().pow(gamma).toFloat()
            }
            if (rank > darkness) {
                continue
            }
            val radius = max(0.5f,
                    dotRadius * (1f + dotSizeVariation.toFloat() * (darkness - 0.5f)))
            val radiusIndex = (radius / radiusStep + 0.5f).toInt()
            val mask = masks.getOrPut(radiusIndex) { DotMask(radiusIndex * radiusStep) }

            var color = dotColor
            if (colorFromImage) {
                val u = tone.sample(tone.uAvg, px, py)
                val v = tone.sample(tone.vAvg, px, py)
                val dy = (yValue * dotLuminanceScale + 0.5).toInt().coerceIn(0, 255)
                val du = (128.0 + (u - 128.0) * chromaBoost + 0.5).toInt().coerceIn(0, 255)
                val dv = (128.0 + (v - 128.0) * chromaBoost + 0.5).toInt().coerceIn(0, 255)
                color = YuvUtils.yuvToRgb(dy, du, dv, true)
            }
            mask.blend(out, width, height, (px + 0.5f).toInt(), (py + 0.5f).toInt(), color)
        }
    }

    /** Image luminance and chroma averaged over square cells, sampled bilinearly. */
    private class ToneMap(
        yData: ByteArray, uData: ByteArray, vData: ByteArray,
        width: Int, height: Int, cell: Int, includeChroma: Boolean,
    ) {
        val cellSize = max(1, cell)
        val mapWidth = (width + cellSize - 1) / cellSize
        val mapHeight = (height + cellSize - 1) / cellSize
        val yAvg = FloatArray(mapWidth * mapHeight)
        val uAvg = FloatArray(if (includeChroma) mapWidth * mapHeight else 0) { 128f }
        val vAvg = FloatArray(if (includeChroma) mapWidth * mapHeight else 0) { 128f }

        init {
            val uvWidth = (width + 1) / 2
            for (my in 0 until mapHeight) {
                val y0 = my * cellSize
                val y1 = min(height, y0 + cellSize)
                for (mx in 0 until mapWidth) {
                    val x0 = mx * cellSize
                    val x1 = min(width, x0 + cellSize)
                    var ySum = 0
                    for (y in y0 until y1) {
                        val rowStart = y * width
                        for (x in x0 until x1) {
                            ySum += yData[rowStart + x].toInt() and 0xFF
                        }
                    }
                    val count = max(1, (y1 - y0) * (x1 - x0))
                    val mi = my * mapWidth + mx
                    yAvg[mi] = ySum.toFloat() / count
                    if (includeChroma) {
                        var uSum = 0
                        var vSum = 0
                        var uvCount = 0
                        for (y in y0 until y1 step 2) {
                            val uvRow = (y / 2) * uvWidth
                            for (x in x0 until x1 step 2) {
                                uSum += uData[uvRow + x / 2].toInt() and 0xFF
                                vSum += vData[uvRow + x / 2].toInt() and 0xFF
                                uvCount++
                            }
                        }
                        if (uvCount > 0) {
                            uAvg[mi] = uSum.toFloat() / uvCount
                            vAvg[mi] = vSum.toFloat() / uvCount
                        }
                    }
                }
            }
        }

        fun sample(channel: FloatArray, x: Float, y: Float): Float {
            val fx = (x / cellSize - 0.5f).coerceIn(0f, (mapWidth - 1).toFloat())
            val fy = (y / cellSize - 0.5f).coerceIn(0f, (mapHeight - 1).toFloat())
            val ix = min(mapWidth - 1, fx.toInt())
            val iy = min(mapHeight - 1, fy.toInt())
            val ix1 = min(mapWidth - 1, ix + 1)
            val iy1 = min(mapHeight - 1, iy + 1)
            val tx = fx - ix
            val ty = fy - iy
            val top = channel[iy * mapWidth + ix] * (1 - tx) + channel[iy * mapWidth + ix1] * tx
            val bottom = channel[iy1 * mapWidth + ix] * (1 - tx) + channel[iy1 * mapWidth + ix1] * tx
            return top * (1 - ty) + bottom * ty
        }
    }

    /** Anti-aliased circular dot with 0-255 coverage values. */
    private class DotMask(radius: Float) {
        val extent = max(0, ceil(radius + 0.5f).toInt())
        private val size = 2 * extent + 1
        private val alpha = IntArray(size * size)

        init {
            for (dy in -extent..extent) {
                for (dx in -extent..extent) {
                    val dist = sqrt((dx * dx + dy * dy).toFloat())
                    val coverage = (radius + 0.5f - dist).coerceIn(0f, 1f)
                    alpha[(dy + extent) * size + (dx + extent)] = (coverage * 255f + 0.5f).toInt()
                }
            }
        }

        fun blend(out: IntArray, width: Int, height: Int, cx: Int, cy: Int, color: Int) {
            val cr = (color shr 16) and 0xFF
            val cg = (color shr 8) and 0xFF
            val cb = color and 0xFF
            val opaque = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
            for (y in max(0, cy - extent)..min(height - 1, cy + extent)) {
                val maskRow = (y - cy + extent) * size
                val outRow = y * width
                for (x in max(0, cx - extent)..min(width - 1, cx + extent)) {
                    val a = alpha[maskRow + (x - cx + extent)]
                    if (a == 0) continue
                    if (a == 255) {
                        out[outRow + x] = opaque
                        continue
                    }
                    val dst = out[outRow + x]
                    val ia = 255 - a
                    val r = (((dst shr 16) and 0xFF) * ia + cr * a + 127) / 255
                    val g = (((dst shr 8) and 0xFF) * ia + cg * a + 127) / 255
                    val b = ((dst and 0xFF) * ia + cb * a + 127) / 255
                    out[outRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    companion object {
        const val EFFECT_NAME = "stipple"
        private const val MIN_SPACING_PIXELS = 3.0

        private val nativeLibraryLoaded = Effect.loadNativeLibrary()

        private external fun generatePoissonPointsNative(
            width: Int, height: Int, minSpacing: Float, numLayers: Int, seed: Int): FloatArray?

        private external fun renderStippleNative(
            yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int,
            points: FloatArray, dotRadius: Float, dotSizeVariation: Float, gamma: Float,
            toneCellSize: Int, backgroundColor: Int, dotColor: Int, colorFromImage: Boolean,
            dotLuminanceScale: Float, chromaBoost: Float, invertTone: Boolean,
            outputPixels: IntArray): Boolean

        private data class PointSetKey(
            val width: Int, val height: Int, val minSpacing: Float, val numLayers: Int, val seed: Int)

        // Generating points is much slower than rendering a frame, so point sets are cached by
        // size. Effects are re-created frequently (e.g. for every tile of the effect selection
        // grid), so the cache is shared across instances. Small LRU to cover the preview,
        // grid tile, and saved photo sizes.
        private val pointCache = object : LinkedHashMap<PointSetKey, FloatArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PointSetKey, FloatArray>) =
                size > 6
        }

        private fun pointsForSize(
                width: Int, height: Int, minSpacing: Float, numLayers: Int, seed: Int): FloatArray {
            val key = PointSetKey(width, height, minSpacing, numLayers, seed)
            synchronized(pointCache) {
                pointCache[key]?.let { return it }
            }
            val t0 = System.nanoTime()
            var points: FloatArray? = null
            if (nativeLibraryLoaded) {
                points = generatePoissonPointsNative(width, height, minSpacing, numLayers, seed)
            }
            if (points == null) {
                points = generatePoissonPointsKotlin(width, height, minSpacing, numLayers, seed)
            }
            Log.i(EFFECT_NAME, "Generated ${points.size / 3} points for ${width}x${height} " +
                    "spacing=$minSpacing in ${(System.nanoTime() - t0) / 1_000_000} ms")
            synchronized(pointCache) {
                pointCache[key] = points
            }
            return points
        }

        /** xorshift32; matches FastRandom in stipple_effect.h. */
        private class FastRandom(seed: Int) {
            private var state: Int = seed * 0x9E3779B1.toInt() + 0x9E3779B9.toInt()
            init {
                if (state == 0) state = 0x9E3779B9.toInt()
            }
            fun nextUInt(): Int {
                var x = state
                x = x xor (x shl 13)
                x = x xor (x ushr 17)
                x = x xor (x shl 5)
                state = x
                return x
            }
            fun nextFloat(): Float = (nextUInt() ushr 8).toFloat() * (1f / 16777216f)
            fun nextIndex(n: Int): Int = ((nextUInt().toLong() and 0xFFFFFFFFL) % n).toInt()
        }

        /**
         * Kotlin implementation of progressive Poisson disk sampling; mirrors
         * Stipple::generateProgressivePoissonPoints in stipple_effect.h. Returns (x, y, rank)
         * triples.
         */
        internal fun generatePoissonPointsKotlin(
                width: Int, height: Int, minSpacing: Float, numLayers: Int, seed: Int): FloatArray {
            if (width <= 0 || height <= 0 || minSpacing <= 0f || numLayers <= 0) {
                return FloatArray(0)
            }
            val rng = FastRandom(seed)
            val candidatesPerPoint = 12
            val twoPi = 6.283185307f
            val fw = width.toFloat()
            val fh = height.toFloat()
            // Growable parallel arrays of point coordinates and ranks.
            var xs = FloatArray(1024)
            var ys = FloatArray(1024)
            var ranks = FloatArray(1024)
            var numPoints = 0

            for (layer in 0 until numLayers) {
                val r = minSpacing * 2.0.pow((numLayers - 1 - layer) * 0.5).toFloat()
                val r2 = r * r
                val cellSize = r / 1.41421356f
                val cols = max(1, ceil(fw / cellSize).toInt())
                val rows = max(1, ceil(fh / cellSize).toInt())
                val grid = IntArray(cols * rows) { -1 }
                fun cellX(x: Float) = min(cols - 1, (x / cellSize).toInt())
                fun cellY(y: Float) = min(rows - 1, (y / cellSize).toInt())

                fun isFarFromExisting(x: Float, y: Float): Boolean {
                    val cx = cellX(x)
                    val cy = cellY(y)
                    if (grid[cy * cols + cx] >= 0) return false
                    for (gy in max(0, cy - 2)..min(rows - 1, cy + 2)) {
                        val edgeRow = (gy == cy - 2 || gy == cy + 2)
                        for (gx in max(0, cx - 2)..min(cols - 1, cx + 2)) {
                            if (edgeRow && (gx == cx - 2 || gx == cx + 2)) continue
                            val idx = grid[gy * cols + gx]
                            if (idx >= 0) {
                                val dx = xs[idx] - x
                                val dy = ys[idx] - y
                                if (dx * dx + dy * dy < r2) return false
                            }
                        }
                    }
                    return true
                }

                val rankMax = 2.0.pow(layer - (numLayers - 1)).toFloat()
                val rankMin = if (layer == 0) 0f else rankMax * 0.5f
                fun newRank() = rankMin + rng.nextFloat() * (rankMax - rankMin)

                fun addPoint(x: Float, y: Float): Int {
                    if (numPoints == xs.size) {
                        xs = xs.copyOf(xs.size * 2)
                        ys = ys.copyOf(ys.size * 2)
                        ranks = ranks.copyOf(ranks.size * 2)
                    }
                    val idx = numPoints++
                    xs[idx] = x
                    ys[idx] = y
                    ranks[idx] = newRank()
                    grid[cellY(y) * cols + cellX(x)] = idx
                    return idx
                }

                var active = IntArray(max(16, numPoints * 2))
                var numActive = 0
                for (i in 0 until numPoints) {
                    grid[cellY(ys[i]) * cols + cellX(xs[i])] = i
                    active[numActive++] = i
                }
                if (numPoints == 0) {
                    active[numActive++] = addPoint(rng.nextFloat() * fw, rng.nextFloat() * fh)
                }

                while (numActive > 0) {
                    val ai = rng.nextIndex(numActive)
                    val baseX = xs[active[ai]]
                    val baseY = ys[active[ai]]
                    var found = false
                    val angleOffset = rng.nextFloat()
                    for (k in 0 until candidatesPerPoint) {
                        val angle = (k + angleOffset) * (twoPi / candidatesPerPoint)
                        val dist = r * (1f + 0.15f * rng.nextFloat())
                        val x = baseX + dist * cos(angle)
                        val y = baseY + dist * sin(angle)
                        if (x < 0f || x >= fw || y < 0f || y >= fh) continue
                        if (!isFarFromExisting(x, y)) continue
                        val idx = addPoint(x, y)
                        if (numActive == active.size) {
                            active = active.copyOf(active.size * 2)
                        }
                        active[numActive++] = idx
                        found = true
                        break
                    }
                    if (!found) {
                        active[ai] = active[numActive - 1]
                        numActive--
                    }
                }
            }

            val result = FloatArray(numPoints * 3)
            for (i in 0 until numPoints) {
                result[3 * i] = xs[i]
                result[3 * i + 1] = ys[i]
                result[3 * i + 2] = ranks[i]
            }
            return result
        }

        private fun luminance(color: Int) =
                YuvUtils.rgbToY(Color.red(color), Color.green(color), Color.blue(color))

        fun fromParameters(params: Map<String, Any>): StippleEffect {
            fun num(key: String, default: Double) =
                    (params.getOrElse(key, { default }) as Number).toDouble()
            fun color(key: String, default: Int) =
                    if (params.containsKey(key)) intFromArgbList(params[key] as List<Int>) else default

            val backgroundColor = color("backgroundColor", Color.WHITE)
            val dotColor = color("dotColor", Color.BLACK)
            val colorMode = params.getOrElse("colorMode", { "fixed" }) as String
            // By default, dots follow dark areas on a light background and light areas on a
            // dark background.
            val invertTone = params.getOrElse("invertTone", { luminance(backgroundColor) < 128 })
                    as Boolean
            return StippleEffect(
                effectParams = params,
                dotsAcross = num("dotsAcross", 300.0).toInt().coerceAtLeast(1),
                numLayers = num("numLayers", 8.0).toInt().coerceIn(1, 16),
                dotRadiusFraction = num("dotRadius", 0.65),
                dotSizeVariation = num("dotSizeVariation", 0.5),
                gamma = num("gamma", 1.0),
                colorFromImage = (colorMode == "image"),
                dotColor = dotColor,
                backgroundColor = backgroundColor,
                invertTone = invertTone,
                dotLuminanceScale = num("dotLuminanceScale", 0.5),
                chromaBoost = num("chromaBoost", 1.5),
                seed = num("seed", 0.0).toInt(),
            )
        }

        fun blackOnWhite() = fromParameters(mapOf(
            "colorMode" to "fixed",
            "dotColor" to listOf(0, 0, 0),
            "backgroundColor" to listOf(255, 255, 255),
        ))

        fun whiteOnBlack() = fromParameters(mapOf(
            "colorMode" to "fixed",
            "dotColor" to listOf(255, 255, 255),
            "backgroundColor" to listOf(0, 0, 0),
        ))

        fun color() = fromParameters(mapOf(
            "colorMode" to "image",
            "backgroundColor" to listOf(255, 255, 255),
        ))
    }
}
