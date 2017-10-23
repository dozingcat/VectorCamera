package com.dozingcatsoftware.boojiecam

import android.renderscript.Allocation
import android.util.Log

/**
 * Created by brian on 9/30/17.
 */
data class CameraImage(val image: PlanarImage?, val allocation: Allocation?,
                       val orientation: ImageOrientation,
                       val status: CameraStatus, val timestamp: Long) {
    var isClosed = false

    init {
        if ((image == null) == (allocation == null)) {
            throw IllegalArgumentException("Exactly one of `image` and `allocation` should be set")
        }
    }

    fun width(): Int {
        return image?.width ?: allocation!!.type.x
    }

    fun height(): Int {
        return image?.height ?: allocation!!.type.y
    }

    fun closeImage() {
        if (image == null) {
            throw IllegalStateException("Image is null")
        }
        if (isClosed) {
            Log.w(TAG, "Image is already closed!")
            return
        }
        image.close()
        isClosed = true
    }

    companion object {
        val TAG = "CameraImage"

        fun withImage(image: PlanarImage, orientation: ImageOrientation,
                      status: CameraStatus, timestamp: Long): CameraImage {
            return CameraImage(image, null, orientation, status, timestamp)
        }

        fun withAllocation(allocation: Allocation, orientation: ImageOrientation,
                           status: CameraStatus, timestamp: Long): CameraImage {
            return CameraImage(null, allocation, orientation, status, timestamp)
        }
    }
}

