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

    fun processImage(context: Context, imageUri: Uri) {
        Log.i(TAG, "Processing image: ${imageUri}")
        val rs = RenderScript.create(context)
        val t1 = System.currentTimeMillis()
        var bitmap: Bitmap? =
                AndroidUtils.scaledBitmapFromURIWithMaximumSize(context, imageUri, 2560, 1600)
        val planarYuv = PlanarYuvAllocations.fromBitmap(rs, bitmap!!)
        val t2 = System.currentTimeMillis()
        Log.i(TAG, "Read bitmap in ${t2-t1} ms")
        val inputImage = CameraImage(null, planarYuv, ImageOrientation.NORMAL,
                CameraStatus.CAPTURING_PHOTO, timeFn(), Size(bitmap!!.width, bitmap!!.height))
        // Free memory before creating the new bitmap.
        bitmap = null
        val prefs = BCPreferences(context)
        val effect = prefs.effect(rs, {throw IllegalStateException()})
        val outputBitmap = effect.createBitmap(inputImage)
        val paintFn = effect.createPaintFn(inputImage)
        val processedBitmap = ProcessedBitmap(effect, inputImage, outputBitmap, paintFn)

        photoLibrary.savePhoto(context, processedBitmap,
                {Log.i(TAG, "Saved photo")},
                {ex -> throw ex})
    }

    companion object {
        val TAG = "ProcessImageOperation"
    }
}