package com.dozingcatsoftware.boojiecam

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Created by brian on 1/28/18.
 */
class ProcessImageOperation {

    fun processImage(context: Context, imageUri: Uri) {
        Log.i(TAG, "Processing image: ${imageUri}")
    }

    companion object {
        val TAG = "ProcessImageOperation"
    }
}