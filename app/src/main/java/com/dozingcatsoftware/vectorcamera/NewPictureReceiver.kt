package com.dozingcatsoftware.vectorcamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receiver for the Camera.ACTION_NEW_PICTURE broadcast message sent when the camera app saves
 * a new picture. Calls ProcessImageOperation to create an ASCII version of the picture.
 */
class NewPictureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // On Android N and later we use a JobService and shouldn't get this notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return
        }
        Log.i(javaClass.name, "Got picture: " + intent.data!!)
        try {
            ProcessImageOperation().processImage(context, intent.data)
        } catch (ex: Exception) {
            Log.e(javaClass.name, "Error saving picture", ex)
        }

    }

}
