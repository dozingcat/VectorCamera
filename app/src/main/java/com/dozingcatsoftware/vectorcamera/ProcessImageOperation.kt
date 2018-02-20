package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.net.Uri
import android.renderscript.RenderScript
import android.util.Log
import android.util.Size
import com.dozingcatsoftware.util.scaledBitmapFromURIWithMaximumSize
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry

class ProcessImageOperation(val timeFn: (() -> Long) = System::currentTimeMillis) {
    private val photoLibrary = PhotoLibrary.defaultLibrary()

    fun processImage(context: Context, imageUri: Uri): String {
        Log.i(TAG, "Processing image: ${imageUri}")
        val t1 = timeFn()
        val rs = RenderScript.create(context)
        // Use a block so the bitmap can be freed as soon as possible.
        val inputImage = run {
            val bitmap = scaledBitmapFromURIWithMaximumSize(context, imageUri, 2560, 1600)
            val planarYuv = PlanarYuvAllocations.fromBitmap(rs, bitmap)
            CameraImage(rs, null, planarYuv, ImageOrientation.NORMAL,
                    CameraStatus.CAPTURING_PHOTO, timeFn(), Size(bitmap.width, bitmap.height))
        }
        val prefs = VCPreferences(context)
        val effect = prefs.effect(rs, {
            EffectRegistry.defaultEffectFactories()[0](rs, prefs.lookupFunction)
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

    companion object {
        const val TAG = "ProcessImageOperation"
    }
}
