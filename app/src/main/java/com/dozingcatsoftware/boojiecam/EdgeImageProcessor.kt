package com.dozingcatsoftware.boojiecam

import android.graphics.*
import android.media.Image
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Created by brian on 10/1/17.
 */
class EdgeImageProcessor(private val colorTable: IntArray,
                         private val paintFn: (PlanarImage, RectF) -> Paint?) :
        CameraImageProcessor() {
    private val threadPool = Executors.newFixedThreadPool(maxThreads)
    private var resultBitmap: Bitmap? = null

    override fun createBitmapFromImage(image: PlanarImage): Bitmap {
        val t1 = System.currentTimeMillis()
        if (resultBitmap == null ||
                resultBitmap!!.width != image.width || resultBitmap!!.height != image.height) {
            resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        }
        val width = image.width
        val height = image.height
        val bright = ByteArray(image.planes[0].buffer.capacity())
        image.planes[0].buffer.get(bright)
        val rowStride = image.planes[0].rowStride
        val pixels = IntArray(width * height)

        val tasks = mutableListOf<Callable<Unit>>()
        for (i in 0 until maxThreads) {
            val minRow = height * i / maxThreads
            val maxRow = height * (i + 1) / maxThreads
            tasks.add(Callable {computeEdges(bright, width, height, minRow, maxRow, rowStride, colorTable, pixels)})
        }
        threadPool.invokeAll(tasks)

        val t2 = System.currentTimeMillis()
        resultBitmap!!.setPixels(pixels, 0, width, 0, 0, width, height)
        val t3 = System.currentTimeMillis()
        timingLog("Created edge bitmap: " + (t2-t1) + " " + (t3-t2))
        return resultBitmap!!
    }

    override fun createPaintFn(image: PlanarImage): (RectF) -> Paint? {
        return {rect -> paintFn(image, rect)}
    }

    private fun computeEdges(bright: ByteArray, width: Int, height: Int, minRow: Int, maxRow: Int,
                             rowStride: Int, colorTable: IntArray, pixels: IntArray) {
        val multiplier = minOf(4, maxOf(2, Math.round(width / 480f)))
        for (y in minRow until maxRow) {
            if (y == 0 || y == height - 1) {
                val pixOffset = y * width
                for (i in pixOffset until pixOffset + width) {
                    pixels[i] = colorTable[0]
                }
            }
            else {
                // Changing the center multiplier from 8 to 9 gives an edge sharpening effect.
                // (If the center and its neighbors are equal, it will be unchanged rather than 0).
                // Taking the absolute value of edge strength makes the edge lines thicker (because
                // both dark->light and light->dark will have high values).
                val minBrightIndex = y * rowStride + 1
                val maxBrightIndex = minBrightIndex + width - 2
                var pixOffset = y * width
                pixels[pixOffset++] = colorTable[0]
                for (index in minBrightIndex until maxBrightIndex) {
                    var up = index - rowStride
                    var down = index + rowStride
                    var edgeStrength = 8 * toUInt(bright[index]) - (
                            toUInt(bright[up-1]) + toUInt(bright[up]) + toUInt(bright[up+1]) +
                                    toUInt(bright[index-1]) + toUInt(bright[index+1]) +
                                    toUInt(bright[down-1]) + toUInt(bright[down]) + toUInt(bright[down+1]))
                    val b = Math.min(255, Math.max(0, multiplier * edgeStrength))
                    pixels[pixOffset++] = colorTable[b]
                }
                pixels[pixOffset++] = colorTable[0]
            }
        }
    }

    external private fun computeEdgesNative(bright: ByteArray,
                                            width: Int, height: Int, minRow: Int, maxRow: Int,
                                            rowStride: Int,
                                            colorTable: IntArray, pixels: IntArray)

    companion object {
        fun withFixedColors(minEdgeColor: Int, maxEdgeColor: Int): EdgeImageProcessor {
            return EdgeImageProcessor(makeRangeColorMap(minEdgeColor, maxEdgeColor), {_, _ -> null})
        }

        fun withLinearGradient(minEdgeColor: Int, gradientStartColor: Int, gradientEndColor: Int)
                : EdgeImageProcessor {
            val paintFn = fun(_: PlanarImage, rect: RectF): Paint {
                val p = Paint()
                p.shader = LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        addAlpha(gradientStartColor), addAlpha(gradientEndColor),
                        Shader.TileMode.MIRROR)
                return p
            }
            return EdgeImageProcessor(makeAlphaColorMap(minEdgeColor), paintFn)
        }

        fun withRadialGradient(minEdgeColor: Int, centerColor: Int, outerColor: Int)
                : EdgeImageProcessor {
            val paintFn = fun(_: PlanarImage, rect: RectF): Paint {
                val p = Paint()
                p.shader = RadialGradient(
                        rect.width() / 2, rect.height() / 2,
                        maxOf(rect.width(), rect.height()) / 2f,
                        addAlpha(centerColor), addAlpha(outerColor), Shader.TileMode.MIRROR)
                return p
            }
            return EdgeImageProcessor(makeAlphaColorMap(minEdgeColor), paintFn)
        }

        private fun makeRangeColorMap(
                minEdgeColor: Int, maxEdgeColor: Int, size: Int=256): IntArray {
            val r0 = (minEdgeColor shr 16) and 0xff
            val g0 = (minEdgeColor shr 8) and 0xff
            val b0 = (minEdgeColor) and 0xff
            val r1 = (maxEdgeColor shr 16) and 0xff
            val g1 = (maxEdgeColor shr 8) and 0xff
            val b1 = (maxEdgeColor) and 0xff
            val sizef = size.toFloat()
            return IntArray(size, fun(index): Int {
                val fraction = index / sizef
                val r = Math.round(r0 + (r1 - r0) * fraction)
                val g = Math.round(g0 + (g1 - g0) * fraction)
                val b = Math.round(b0 + (b1 - b0) * fraction)
                return (0xff shl 24) or (r shl 16) or (g shl 8) or b
            })
        }

        private fun makeAlphaColorMap(color: Int): IntArray {
            val colorWithoutAlpha = 0xffffff and color
            return IntArray(256, {i -> ((255 - i) shl 24) or colorWithoutAlpha})
        }
    }
}