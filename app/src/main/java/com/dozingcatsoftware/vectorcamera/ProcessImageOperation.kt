package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dozingcatsoftware.util.scaledBitmapFromURIWithMaximumSize
import com.dozingcatsoftware.util.YuvImageBuffers
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry

class ProcessImageOperation(val timeFn: (() -> Long) = System::currentTimeMillis) {

    fun processImage(context: Context, imageUri: Uri): String {
        val photoLibrary = PhotoLibrary.defaultLibrary(context)
        Log.i(TAG, "Processing image: ${imageUri}")
        val t1 = timeFn()
        // Use a block so the bitmap can be freed as soon as possible.
        val inputImage = run {
            val bitmap = scaledBitmapFromURIWithMaximumSize(context, imageUri, 2560, 1600)
            val yuvBuffers = YuvImageBuffers.fromBitmap(bitmap)
            val yuvBytes = createYuvBytesFromBuffers(yuvBuffers)
            bitmap.recycle()
            CameraImage.fromYuvBytes(yuvBytes, yuvBuffers.width, yuvBuffers.height,
                    ImageOrientation.NORMAL, CameraStatus.CAPTURING_PHOTO, timeFn())
        }
        val prefs = VCPreferences(context)
        val effect = prefs.effect({
            EffectRegistry().defaultEffectAtIndex(0, prefs.lookupFunction)
        })
        val t2 = timeFn()
        val outputBitmap = effect.createBitmap(inputImage)
        val processedBitmap = ProcessedBitmap(effect, inputImage, outputBitmap)

        val t3 = timeFn()
        val photoId = photoLibrary.savePhoto(context, processedBitmap)
        val t4 = timeFn()
        Log.i(TAG, "Image processed in ${t4-t1}ms (${t2-t1} ${t3-t2} ${t4-t3})")
        return photoId
    }

    /**
     * Converts YuvImageBuffers to the flattened byte array format expected by CameraImage.
     */
    private fun createYuvBytesFromBuffers(yuvBuffers: YuvImageBuffers): ByteArray {
        val ySize = yuvBuffers.y.size
        val uSize = yuvBuffers.u.size
        val vSize = yuvBuffers.v.size
        val totalSize = ySize + uSize + vSize
        
        val result = ByteArray(totalSize)
        System.arraycopy(yuvBuffers.y, 0, result, 0, ySize)
        System.arraycopy(yuvBuffers.u, 0, result, ySize, uSize)
        System.arraycopy(yuvBuffers.v, 0, result, ySize + uSize, vSize)
        
        return result
    }

    companion object {
        const val TAG = "ProcessImageOperation"
    }
}
