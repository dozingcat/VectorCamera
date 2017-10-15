package com.dozingcatsoftware.boojiecam

import android.graphics.ImageFormat
import java.io.OutputStream
import java.nio.ByteBuffer

inline fun toUInt(b: Byte): Int {
    return b.toInt() and 0xff
}

fun getBufferBytes(buffer: ByteBuffer): ByteArray {
    if (buffer.hasArray() && buffer.arrayOffset() == 0) {
        val arr = buffer.array()
        if (arr.size == buffer.limit()) {
            return arr
        }
    }
    val arr = ByteArray(buffer.limit())
    buffer.get(arr)
    return arr
}

fun flattenedYuvImageBytes(image: PlanarImage): ByteArray {
    if (image.format != ImageFormat.YUV_420_888) {
        throw IllegalArgumentException("Unexpected image format: " + image.format)
    }
    val uvWidth = image.width / 2
    val uvHeight = image.height / 2
    val outputBytes = ByteArray(image.width * image.height + 2 * uvWidth * uvHeight)
    var outputIndex = 0

    fun appendBytes(plane: PlanarImage.Plane, width: Int, height: Int) {
        val planeBytes = getBufferBytes(plane.buffer)
        val pixelStride = plane.pixelStride
        for (y in 0 until height) {
            var offset = y * plane.rowStride
            for (x in 0 until width) {
                outputBytes[outputIndex++] = planeBytes[offset]
                offset += pixelStride
            }
        }
    }

    appendBytes(image.planes[0], image.width, image.height)
    appendBytes(image.planes[1], uvWidth, uvHeight)
    appendBytes(image.planes[2], uvWidth, uvHeight)
    return outputBytes
}

fun writeBufferToOuptutStream(buffer: ByteBuffer, output: OutputStream) {
    if (buffer.hasArray()) {
        val arr = buffer.array()
        output.write(arr, buffer.arrayOffset(), buffer.limit())
    }
    else {
        val arr = ByteArray(buffer.limit())
        buffer.get(arr)
        output.write(arr)
    }
}
