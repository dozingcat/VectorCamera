package com.dozingcatsoftware.vectorcamera

import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicResize
import android.util.Size
import com.dozingcatsoftware.util.create2dAllocation

/**
 * An image to be processed, which can come directly from a camera input or from an existing image.
 * The image data is either a YUV allocation in `singleYuvAllocation`, or separate Y/U/V allocations
 * in `planarYuvAllocations`.
 */
data class CameraImage(val rs: RenderScript,
                       val singleYuvAllocation: Allocation?,
                       val planarYuvAllocations: PlanarYuvAllocations?,
                       val orientation: ImageOrientation, val status: CameraStatus,
                       val timestamp: Long, val displaySize: Size = zeroSize) {

    // width() and height() return the dimensions of the actual camera input, which is always
    // landscape. In portrait orientation the rendered image will have the dimensioned swapped.
    fun width(): Int {
        return singleYuvAllocation?.type?.x ?: planarYuvAllocations!!.y.type.x
    }

    fun height(): Int {
        return singleYuvAllocation?.type?.y ?: planarYuvAllocations!!.y.type.y
    }

    fun size() = Size(width(), height())

    fun resizedTo(size: Size): CameraImage {
        val resizeScript = ScriptIntrinsicResize.create(rs)

        fun doResize(inputAlloc: Allocation, w: Int, h: Int): Allocation {
            val outputAlloc = create2dAllocation(rs, Element::U8, w, h)
            resizeScript.setInput(inputAlloc)
            resizeScript.forEach_bicubic(outputAlloc)
            return outputAlloc
        }

        return if (singleYuvAllocation != null) {
            val outputAlloc = doResize(singleYuvAllocation, size.width, size.height)
            copy(singleYuvAllocation = outputAlloc)
        }
        else {
            val planes = planarYuvAllocations!!
            val yOutput = doResize(planes.y, size.width, size.height)
            val uOutput = doResize(planes.u, size.width / 2, size.height / 2)
            val vOutput = doResize(planes.v, size.width / 2, size.height / 2)
            copy(planarYuvAllocations = PlanarYuvAllocations(yOutput, uOutput, vOutput))
        }
    }

    companion object {
        private val zeroSize = Size(0, 0)

        fun withAllocation(rs: RenderScript, allocation: Allocation, orientation: ImageOrientation,
                           status: CameraStatus, timestamp: Long,
                           displaySize: Size = zeroSize): CameraImage {
            return CameraImage(rs, allocation, null, orientation, status, timestamp, displaySize)
        }

        fun withAllocationSet(rs: RenderScript, yuv: PlanarYuvAllocations,
                              orientation: ImageOrientation, status: CameraStatus, timestamp: Long,
                              displaySize: Size = zeroSize): CameraImage {
            return CameraImage(rs, null, yuv, orientation, status, timestamp, displaySize)
        }
    }
}
