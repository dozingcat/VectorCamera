package com.dozingcatsoftware.boojiecam

import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type
import java.io.OutputStream
import java.nio.ByteBuffer

inline fun toUInt(b: Byte): Int {
    return b.toInt() and 0xff
}

inline fun addAlpha(color: Int): Int {
    return 0xff000000.toInt() or color
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

fun ioReceiveIfInput(alloc: Allocation?) {
    if (alloc != null && (alloc.usage and Allocation.USAGE_IO_INPUT != 0)) {
        alloc.ioReceive()
    }
}

fun allocationHas2DSize(alloc: Allocation?, x: Int, y: Int): Boolean {
    return (alloc != null) && (alloc.type.x) == x && (alloc.type.y == y)
}

fun create2dAllocation(rs: RenderScript, elementFn: (RenderScript) -> Element, x: Int, y: Int,
                       usage: Int = Allocation.USAGE_SCRIPT): Allocation {
    val typeBuilder = Type.Builder(rs, elementFn(rs)).setX(x).setY(y)
    return Allocation.createTyped(rs, typeBuilder.create(), usage)
}
