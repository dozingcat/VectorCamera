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

fun argbArrayFromInt(argb: Int): IntArray {
    return intArrayOf(argb shr 24, (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
}

fun intFromArgbArray(a: IntArray): Int {
    return (a[0] shl 24) or (a[1] shl 16) or (a[2] shl 8) or a[3]
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

fun makeAllocationColorMap(rs: RenderScript,
                           minEdgeColor: Int, maxEdgeColor: Int, size: Int=256): Allocation {
    val r0 = (minEdgeColor shr 16) and 0xff
    val g0 = (minEdgeColor shr 8) and 0xff
    val b0 = (minEdgeColor) and 0xff
    val r1 = (maxEdgeColor shr 16) and 0xff
    val g1 = (maxEdgeColor shr 8) and 0xff
    val b1 = (maxEdgeColor) and 0xff
    val sizef = size.toFloat()

    val colors = ByteArray(size * 4)
    var bindex = 0
    for (index in 0 until size) {
        val fraction = index / sizef
        // Allocations are RGBA even though bitmaps are ARGB.
        colors[bindex++] = Math.round(r0 + (r1 - r0) * fraction).toByte()
        colors[bindex++] = Math.round(g0 + (g1 - g0) * fraction).toByte()
        colors[bindex++] = Math.round(b0 + (b1 - b0) * fraction).toByte()
        colors[bindex++] = 0xff.toByte()
    }
    val type = Type.Builder(rs, Element.RGBA_8888(rs))
    type.setX(size)
    val allocation = Allocation.createTyped(rs, type.create(), Allocation.USAGE_SCRIPT)
    allocation.copyFrom(colors)
    return allocation
}

fun makeAlphaAllocation(rs: RenderScript, color: Int): Allocation {
    val r = ((color shr 16) and 0xff).toByte()
    val g = ((color shr 8) and 0xff).toByte()
    val b = ((color) and 0xff).toByte()
    val colors = ByteArray(4 * 256)
    var bindex = 0
    for (index in 0 until 256) {
        colors[bindex++] = r
        colors[bindex++] = g
        colors[bindex++] = b
        colors[bindex++] = (255 - index).toByte()
    }
    val type = Type.Builder(rs, Element.RGBA_8888(rs))
    type.setX(256)
    val allocation = Allocation.createTyped(rs, type.create(), Allocation.USAGE_SCRIPT)
    allocation.copyFrom(colors)
    return allocation
}
