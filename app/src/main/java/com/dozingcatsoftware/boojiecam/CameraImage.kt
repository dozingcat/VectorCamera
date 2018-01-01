package com.dozingcatsoftware.boojiecam

import android.renderscript.Allocation

/**
 * Created by brian on 9/30/17.
 */
data class CameraImage(val singleYuvAllocation: Allocation?,
                       val planarYuvAllocations: PlanarYuvAllocations?,
                       val orientation: ImageOrientation, val status: CameraStatus,
                       val timestamp: Long) {

    fun width(): Int {
        return singleYuvAllocation?.type?.x ?: planarYuvAllocations!!.y.type.x
    }

    fun height(): Int {
        return singleYuvAllocation?.type?.y ?: planarYuvAllocations!!.y.type.y
    }

    companion object {
        fun withAllocation(allocation: Allocation, orientation: ImageOrientation,
                           status: CameraStatus, timestamp: Long): CameraImage {
            return CameraImage(allocation, null, orientation, status, timestamp)
        }

        fun withAllocationSet(yuv: PlanarYuvAllocations, orientation: ImageOrientation,
                              status: CameraStatus, timestamp: Long): CameraImage {
            return CameraImage(null, yuv, orientation, status, timestamp)
        }
    }
}
