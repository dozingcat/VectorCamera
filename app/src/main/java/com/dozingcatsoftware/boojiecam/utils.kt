package com.dozingcatsoftware.boojiecam

import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type

inline fun toUInt(b: Byte): Int {
    return b.toInt() and 0xff
}

inline fun addAlpha(color: Int): Int {
    return 0xff000000.toInt() or color
}

fun flattenedYuvImageBytes(rs: RenderScript, yuvAlloc: Allocation): ByteArray {
    // There's no way to directly read the U and V bytes from a YUV allocation(?), but we can
    // use a .rs script to extract the three planes into output allocations and combine them.
    val width = yuvAlloc.type.x
    val height = yuvAlloc.type.y
    val yAlloc = create2dAllocation(rs, Element::U8, width, height)
    val uvWidth = Math.ceil(width / 2.0).toInt()
    val uvHeight = Math.ceil(height / 2.0).toInt()
    val uAlloc = create2dAllocation(rs, Element::U8, uvWidth, uvHeight)
    val vAlloc = create2dAllocation(rs, Element::U8, uvWidth, uvHeight)

    val script = ScriptC_flatten_yuv(rs)
    script._yuvInputAlloc = yuvAlloc
    script._uOutputAlloc = uAlloc
    script._vOutputAlloc = vAlloc
    script.forEach_flattenYuv(yAlloc)

    val ySize = width * height
    val uvSize = uvWidth * uvHeight
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
