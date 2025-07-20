package com.dozingcatsoftware.util

import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type

// Removed flattenedYuvImageBytes function - RenderScript flatten_yuv script deleted
// This function is no longer needed as we handle YUV bytes directly in CameraImage

fun ioReceiveIfInput(alloc: Allocation?) {
    if (alloc != null && (alloc.usage and Allocation.USAGE_IO_INPUT != 0)) {
        alloc.ioReceive()
    }
}

fun create2dAllocation(rs: RenderScript, elementFn: (RenderScript) -> Element, x: Int, y: Int,
                       usage: Int = Allocation.USAGE_SCRIPT): Allocation {
    val typeBuilder = Type.Builder(rs, elementFn(rs)).setX(x).setY(y)
    return Allocation.createTyped(rs, typeBuilder.create(), usage)
}

/**
 * If `alloc` is non-null and has dimensions `x` and `y`, returns it. Otherwise creates
 * and returns a new Allocation with dimensions `x` and `y` and type given by `elementFn`.
 * Specifically does not check if an existing allocation has a compatible element type.
 */
fun reuseOrCreate2dAllocation(
        alloc: Allocation?, rs: RenderScript, elementFn: (RenderScript) -> Element, x: Int, y: Int,
        usage: Int = Allocation.USAGE_SCRIPT): Allocation {
    if (alloc != null && (alloc.type.x) == x && alloc.type.y == y) {
        return alloc
    }
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
    // Premultiplied alpha
    // https://stackoverflow.com/questions/12310400/how-does-android-apply-alpha-channel-when-using-copypixelstobuffer
    val r = (color shr 16) and 0xff
    val g = (color shr 8) and 0xff
    val b = color and 0xff
    val colors = ByteArray(4 * 256)
    var bindex = 0
    for (index in 0 until 256) {
        val alpha = 255 - index
        colors[bindex++] = (r * alpha / 255).toByte()
        colors[bindex++] = (g * alpha / 255).toByte()
        colors[bindex++] = (b * alpha / 255).toByte()
        colors[bindex++] = alpha.toByte()
    }
    val type = Type.Builder(rs, Element.RGBA_8888(rs))
    type.setX(256)
    val allocation = Allocation.createTyped(rs, type.create(), Allocation.USAGE_SCRIPT)
    allocation.copyFrom(colors)
    return allocation
}
