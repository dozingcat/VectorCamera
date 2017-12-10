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
        return if (singleYuvAllocation != null) singleYuvAllocation.type.x
               else planarYuvAllocations!!.y.type.x
    }

    fun height(): Int {
        return if (singleYuvAllocation != null) singleYuvAllocation.type.y
        else planarYuvAllocations!!.y.type.y
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

