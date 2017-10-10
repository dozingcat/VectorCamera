package com.dozingcatsoftware.boojiecam

import android.media.Image
import android.util.Log

/**
 * Created by brian on 9/30/17.
 */
data class CameraImage(val image: PlanarImage, val orientation: ImageOrientation,
                       val status: CameraStatus, val timestamp: Long) {
    var isClosed = false

    fun close() {
        if (isClosed) {
            Log.w("CameraImage", "Image is already closed!")
        }
        else {
            image.close()
            isClosed = true
        }
    }
}
