package com.dozingcatsoftware.vectorcamera

import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.util.YuvImageBuffers
import com.dozingcatsoftware.util.create2dAllocation
import com.dozingcatsoftware.util.readBytesIntoBuffer
import java.io.InputStream

/**
 * Wrapper for YUV image data stored in separate allocations. This is used when creating CameraImage
 * objects from existing images, because there's no API to create single YUV allocations.
 */
class PlanarYuvAllocations(val y: Allocation, val u: Allocation, val v: Allocation) {

    companion object {
        // Assumes data is in the format [YYYY...][UU...][VV...]
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
            val yuvBuffers = YuvImageBuffers.fromBitmap(bitmap)
            val width = yuvBuffers.width
            val height = yuvBuffers.height

            val yAlloc = create2dAllocation(rs, Element::U8, width, height)
            yAlloc.copyFrom(yuvBuffers.y)
            val uAlloc = create2dAllocation(rs, Element::U8, width / 2, height / 2)
            uAlloc.copyFrom(yuvBuffers.u)
            val vAlloc = create2dAllocation(rs, Element::U8, width / 2, height / 2)
            vAlloc.copyFrom(yuvBuffers.v)

            return PlanarYuvAllocations(yAlloc, uAlloc, vAlloc)
        }
    }
}
