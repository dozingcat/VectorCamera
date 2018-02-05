package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.util.create2dAllocation
import com.dozingcatsoftware.util.readBytesIntoBuffer
import java.io.InputStream

class PlanarYuvAllocations(val y: Allocation, val u: Allocation, val v: Allocation) {

    companion object {
        fun fromInputStream(rs: RenderScript, input: InputStream,
                            width: Int, height: Int): PlanarYuvAllocations {
            val yAlloc = create2dAllocation(rs, Element::U8, width, height)
            val uvWidth = Math.ceil(width / 2.0).toInt()
            val uvHeight = Math.ceil(height / 2.0).toInt()
            val uAlloc = create2dAllocation(rs, Element::U8, uvWidth, uvHeight)
            val vAlloc = create2dAllocation(rs, Element::U8, uvWidth, uvHeight)

            var buffer = ByteArray(width * height)
            readBytesIntoBuffer(input, buffer.size, buffer)
            yAlloc.copyFrom(buffer)
            buffer = ByteArray(uvWidth * uvHeight)
            readBytesIntoBuffer(input, buffer.size, buffer)
            uAlloc.copyFrom(buffer)
            readBytesIntoBuffer(input, buffer.size, buffer)
            vAlloc.copyFrom(buffer)
            return PlanarYuvAllocations(yAlloc, uAlloc, vAlloc)
        }

        // This would be faster in RenderScript or C, but it's not performance-critical
        // and this method saves memory by using a pixel buffer with two rows.
        fun fromBitmap(rs: RenderScript, bitmap: Bitmap): PlanarYuvAllocations {
            // Force width and height to be even.
            val width = bitmap.width - (bitmap.width and 1)
            val height = bitmap.height - (bitmap.height and 1)

            val yBuffer = ByteArray(width * height)
            val uBuffer = ByteArray(width * height / 4)
            val vBuffer = ByteArray(width * height / 4)

            // Read two rows at a time and convert to YUV.
            // https://www.fourcc.org/fccyvrgb.php.
            val pixBuffer = IntArray(2 * width)
            val rb = IntArray(2 * width)
            val gb = IntArray(2 * width)
            val bb = IntArray(2 * width)

            var yIndex = 0
            var uvIndex = 0

            for (r2 in 0 until height / 2) {
                bitmap.getPixels(pixBuffer, 0, width, 0, 2 * r2, width, 2)
                for (i in 0 until pixBuffer.size) {
                    rb[i] = (pixBuffer[i] and 0xff0000) shr 16
                    gb[i] = (pixBuffer[i] and 0x00ff00) shr 8
                    bb[i] = (pixBuffer[i] and 0x0000ff)
                    yBuffer[yIndex++] =
                            clampToUnsignedByte(0.299 * rb[i] + 0.587 * gb[i] + 0.114 * bb[i])
                }
                // For U and V planes, average the pixels in 2x2 blocks.
                for (i in 0 until width / 2) {
                    val x = 2 * i
                    val ra = (rb[x] + rb[x + 1] + rb[x + width] + rb[x + width + 1]) / 4
                    val ga = (gb[x] + gb[x + 1] + gb[x + width] + gb[x + width + 1]) / 4
                    val ba = (bb[x] + rb[x + 1] + bb[x + width] + bb[x + width + 1]) / 4
                    val y = 0.299 * ra + 0.587 * ga + 0.114 * ba
                    uBuffer[uvIndex] = clampToUnsignedByte((ba - y) * 0.565 + 128)
                    vBuffer[uvIndex] = clampToUnsignedByte((ra - y) * 0.713 + 128)
                    uvIndex += 1
                }
            }

            val yAlloc = create2dAllocation(rs, Element::U8, width, height)
            yAlloc.copyFrom(yBuffer)
            val uAlloc = create2dAllocation(rs, Element::U8, width / 2, height / 2)
            uAlloc.copyFrom(uBuffer)
            val vAlloc = create2dAllocation(rs, Element::U8, width / 2, height / 2)
            vAlloc.copyFrom(vBuffer)

            return PlanarYuvAllocations(yAlloc, uAlloc, vAlloc)
        }
    }
}

private inline fun clampToUnsignedByte(v: Double): Byte {
    return (Math.max(0, Math.min(255, v.toInt())) and 0xff).toByte()
}
