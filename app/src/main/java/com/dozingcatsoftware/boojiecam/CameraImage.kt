package com.dozingcatsoftware.boojiecam

import android.renderscript.Allocation
import android.util.Log

/**
 * Created by brian on 9/30/17.
 */
data class CameraImage(val allocation: Allocation, val orientation: ImageOrientation,
                       val status: CameraStatus, val timestamp: Long) {

    fun width(): Int {
        return allocation.type.x
    }

    fun height(): Int {
        return allocation.type.y
    }

    companion object {
        fun withAllocation(allocation: Allocation, orientation: ImageOrientation,
                           status: CameraStatus, timestamp: Long): CameraImage {
            return CameraImage(allocation, orientation, status, timestamp)
        }
    }
}

