package com.dozingcatsoftware.boojiecam

import android.graphics.*
import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class EdgeColorImageProcessor: CameraImageProcessor() {
    private val threadPool = Executors.newFixedThreadPool(maxThreads)

    override fun createBitmapFromImage(image: PlanarImage): Bitmap {
        val t1 = System.currentTimeMillis()
        val width = image.width
        val height = image.height

        val bright = ByteArray(image.planes[0].buffer.capacity())
        val uBytes = ByteArray(image.planes[1].buffer.capacity())
        val vBytes = ByteArray(image.planes[2].buffer.capacity())
        image.planes[0].buffer.get(bright)
        image.planes[1].buffer.get(uBytes)
        image.planes[2].buffer.get(vBytes)

        val yRowStride = image.planes[0].rowStride
        val uRowStride = image.planes[1].rowStride
        val uPixelStride = image.planes[1].pixelStride
        val edgeYuv = ByteArray(bright.size * 3 / 2)

        val tasks = mutableListOf<Callable<Unit>>()
        for (i in 0 until maxThreads) {
            val minRow = height * i / maxThreads
            val maxRow = height * (i + 1) / maxThreads
            tasks.add(Callable {computeEdges(bright, uBytes, vBytes, width, height, minRow, maxRow,
                    yRowStride, uRowStride, uPixelStride, edgeYuv)})
        }
        val t2 = System.currentTimeMillis()
        threadPool.invokeAll(tasks)
        val t3 = System.currentTimeMillis()

        val yuvImage = YuvImage(edgeYuv, ImageFormat.NV21, width, height, null)
        val outStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, outStream)
        val edgeBitmap = BitmapFactory.decodeByteArray(outStream.toByteArray(), 0, outStream.size())
        val t4 = System.currentTimeMillis()
        timingLog("Created edge bitmap: " + (t2-t1) + " " + (t3-t2) + " " + (t4-t3))
        return edgeBitmap
    }

    private fun computeEdges(bright: ByteArray, uBytes: ByteArray, vBytes: ByteArray,
                             width: Int, height: Int, minRow: Int, maxRow: Int,
                             yRowStride: Int, uRowStride: Int, uPixelStride: Int,
                             edgeYuv: ByteArray) {
        val multiplier = minOf(4, maxOf(2, Math.round(width / 480f)))
        // Convert Y channel (luminance) to edge strength, leave U and V channels unmodified.
        // This causes solid areas to keep their color but be shifted darker.
        // http://softpixel.com/~cwright/programming/colorspace/yuv/
        var yuvIndex = minRow * width
        for (y in minRow until maxRow) {
            if (y == 0 || y == height - 1) {
                for (i in 0 until width) {
                    edgeYuv[yuvIndex++] = 0
                }
            }
            else {
                val minIndex = y * yRowStride + 1
                val maxIndex = minIndex + width - 2
                edgeYuv[yuvIndex++] = 0
                for (yIndex in minIndex until maxIndex) {
                    var up = yIndex - yRowStride
                    var down = yIndex + yRowStride
                    var edgeStrength = 8 * toUInt(bright[yIndex]) - (
                            toUInt(bright[up-1]) + toUInt(bright[up]) + toUInt(bright[up+1]) +
                                    toUInt(bright[yIndex-1]) + toUInt(bright[yIndex+1]) +
                                    toUInt(bright[down-1]) + toUInt(bright[down]) + toUInt(bright[down+1]))
                    edgeYuv[yuvIndex++] = minOf(255, maxOf(0, multiplier * edgeStrength)).toByte()
                }
                edgeYuv[yuvIndex++] = 0
            }
        }

        var minUVRow = minRow / 2 + (minRow % 2)
        var maxUVRow = maxRow / 2
        // Interleaved?
        val uvWidth = width / 2
        yuvIndex = width * height + 2 * minUVRow * uvWidth
        for (y in minUVRow until maxUVRow) {
            var uvIndex =  y * uRowStride
            for (x in 0 until uvWidth) {
                edgeYuv[yuvIndex++] = vBytes[uvIndex]
                edgeYuv[yuvIndex++] = uBytes[uvIndex]
                uvIndex += uPixelStride
            }
        }
    }
}