package com.dozingcatsoftware.vectorcamera

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

object PermissionsChecker {

    const val CAMERA_AND_STORAGE_REQUEST_CODE = 1001

    // Permissions for read/write access to external storage are not required because
    // we're only using private app storage.

    private fun hasPermission(activity: Activity, perm: String): Boolean {
        return activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCameraPermission(activity: Activity): Boolean {
        return hasPermission(activity, Manifest.permission.CAMERA)
    }

    fun hasMicrophonePermission(activity: Activity): Boolean {
        return hasPermission(activity, Manifest.permission.RECORD_AUDIO)
    }

    fun requestCameraAndStoragePermissions(activity: Activity) {
        activity.requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO),
                CAMERA_AND_STORAGE_REQUEST_CODE)
    }
}
