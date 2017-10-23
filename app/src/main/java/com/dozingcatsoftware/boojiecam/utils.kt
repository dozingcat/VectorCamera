package com.dozingcatsoftware.boojiecam

import android.graphics.ImageFormat
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type
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

inline fun addAlpha(color: Int): Int {
    return 0xff000000.toInt() or color
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

fun flattenedYuvImageBytes(rs: RenderScript, yuvAlloc: Allocation): ByteArray {
    // There's no way to directly read the U and V bytes from a YUV allocation(?), but we can
    // use a .rs script to extract the three planes into output allocations and combine them.
    val yType = Type.Builder(rs, Element.U8(rs))
    yType.setX(yuvAlloc.type.x)
    yType.setY(yuvAlloc.type.y)
    val uvType = Type.Builder(rs, Element.U8(rs))
    uvType.setX(Math.ceil(yuvAlloc.type.x / 2.0).toInt())
    uvType.setY(Math.ceil(yuvAlloc.type.y / 2.0).toInt())
    val yAlloc = Allocation.createTyped(rs, yType.create(), Allocation.USAGE_SCRIPT)
    val uAlloc = Allocation.createTyped(rs, uvType.create(), Allocation.USAGE_SCRIPT)
    val vAlloc = Allocation.createTyped(rs, uvType.create(), Allocation.USAGE_SCRIPT)

    val script = ScriptC_flatten_yuv(rs)
    script._yuvInputAlloc = yuvAlloc
    script._uOutputAlloc = uAlloc
    script._vOutputAlloc = vAlloc
    script.forEach_flattenYuv(yAlloc)

    val ySize = yAlloc.type.x * yAlloc.type.y
    val uvSize = uAlloc.type.x * uAlloc.type.y
    val outputBytes = ByteArray(ySize + 2 * uvSize)
    val outBuffer = ByteArray(ySize)
    yAlloc.copyTo(outBuffer)
    System.arraycopy(outBuffer, 0, outputBytes, 0, ySize)
    uAlloc.copyTo(outBuffer)
    System.arraycopy(outBuffer, 0, outputBytes, ySize, uvSize)
    vAlloc.copyTo(outBuffer)
    System.arraycopy(outBuffer, 0, outputBytes, ySize + uvSize, uvSize)
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

fun ioReceiveIfInput(alloc: Allocation?) {
    if (alloc != null && (alloc.usage and Allocation.USAGE_IO_INPUT != 0)) {
        alloc.ioReceive()
    }
}

fun copyAllocation(rs: RenderScript, alloc: Allocation): Allocation {
    val data = ByteArray(alloc.bytesSize)
    alloc.copyTo(data)
    val newAlloc = Allocation.createTyped(rs, alloc.type, Allocation.USAGE_SCRIPT)
    newAlloc.copyFrom(data)
    return newAlloc
}