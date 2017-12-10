package com.dozingcatsoftware.boojiecam

import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
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
            input.read(buffer)
            yAlloc.copyFrom(buffer)
            buffer = ByteArray(uvWidth * uvHeight)
            input.read(buffer)
            uAlloc.copyFrom(buffer)
            input.read(buffer)
            vAlloc.copyFrom(buffer)
            return PlanarYuvAllocations(yAlloc, uAlloc, vAlloc)
        }
    }
}
