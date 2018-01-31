package com.dozingcatsoftware.boojiecam

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.renderscript.RenderScript
import android.util.Log
import android.util.Size
import com.dozingcatsoftware.util.AndroidUtils

/**
 * Created by brian on 1/28/18.
 */
class ProcessImageOperation(val timeFn: (() -> Long) = System::currentTimeMillis) {
    val photoLibrary = PhotoLibrary.defaultLibrary()

    fun processImage(context: Context, imageUri: Uri): String {
        Log.i(TAG, "Processing image: ${imageUri}")
        val rs = RenderScript.create(context)
        val t1 = timeFn()
        var bitmap: Bitmap? =
                AndroidUtils.scaledBitmapFromURIWithMaximumSize(context, imageUri, 2560, 1600)
        val planarYuv = PlanarYuvAllocations.fromBitmap(rs, bitmap!!)
        val t2 = timeFn()
        Log.i(TAG, "Read bitmap in ${t2-t1} ms")
        val inputImage = CameraImage(null, planarYuv, ImageOrientation.NORMAL,
                CameraStatus.CAPTURING_PHOTO, timeFn(), Size(bitmap!!.width, bitmap!!.height))
        // Free memory before creating the new bitmap.
        bitmap = null
        val prefs = BCPreferences(context)
        val effect = prefs.effect(rs, {throw IllegalStateException()})
        val t3 = timeFn()
        val outputBitmap = effect.createBitmap(inputImage)
        val paintFn = effect.createPaintFn(inputImage)
        val processedBitmap = ProcessedBitmap(effect, inputImage, outputBitmap, paintFn)

        val t4 = timeFn()
        val photoId = photoLibrary.savePhoto(context, processedBitmap)
        return photoId
    }

    companion object {
        const val TAG = "ProcessImageOperation"
    }
}