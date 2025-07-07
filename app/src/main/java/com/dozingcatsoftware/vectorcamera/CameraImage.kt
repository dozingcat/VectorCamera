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
                       private val _singleYuvAllocation: Allocation?,
                       private val _planarYuvAllocations: PlanarYuvAllocations?,
                       val orientation: ImageOrientation, val status: CameraStatus,
                       val timestamp: Long, val displaySize: Size = zeroSize) {

    // width() and height() return the dimensions of the actual camera input, which is always
    // landscape. In portrait orientation the rendered image will have the dimensioned swapped.
    fun width(): Int {
        return _singleYuvAllocation?.type?.x ?: _planarYuvAllocations!!.y.type.x
    }

    fun height(): Int {
        return _singleYuvAllocation?.type?.y ?: _planarYuvAllocations!!.y.type.y
    }

    fun size() = Size(width(), height())

    /**
     * Returns the primary YUV allocation for RenderScript effects.
     * For single YUV allocations, returns the allocation directly.
     * For planar allocations, returns the Y (luminance) plane.
     */
    fun getPrimaryYuvAllocation(): Allocation {
        return _singleYuvAllocation ?: _planarYuvAllocations!!.y
    }

    /**
     * Returns the single YUV allocation if available, null otherwise.
     */
    fun getSingleYuvAllocation(): Allocation? {
        return _singleYuvAllocation
    }

    /**
     * Returns the planar YUV allocations if available, null otherwise.
     */
    fun getPlanarYuvAllocations(): PlanarYuvAllocations? {
        return _planarYuvAllocations
    }

    /**
     * Returns true if this image uses planar YUV allocations (separate Y, U, V).
     */
    fun hasPlanarYuv(): Boolean {
        return _planarYuvAllocations != null
    }

    /**
     * Returns true if this image uses a single YUV allocation.
     */
    fun hasSingleYuv(): Boolean {
        return _singleYuvAllocation != null
    }

    fun resizedTo(size: Size): CameraImage {
        val resizeScript = ScriptIntrinsicResize.create(rs)

        fun doResize(inputAlloc: Allocation, w: Int, h: Int): Allocation {
            val outputAlloc = create2dAllocation(rs, Element::U8, w, h)
            resizeScript.setInput(inputAlloc)
            resizeScript.forEach_bicubic(outputAlloc)
            return outputAlloc
        }

        return if (_singleYuvAllocation != null) {
            val outputAlloc = doResize(_singleYuvAllocation, size.width, size.height)
            copy(_singleYuvAllocation = outputAlloc)
        }
        else {
            val planes = _planarYuvAllocations!!
            val yOutput = doResize(planes.y, size.width, size.height)
            val uOutput = doResize(planes.u, size.width / 2, size.height / 2)
            val vOutput = doResize(planes.v, size.width / 2, size.height / 2)
            copy(_planarYuvAllocations = PlanarYuvAllocations(yOutput, uOutput, vOutput))
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
