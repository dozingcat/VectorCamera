package com.dozingcatsoftware.vectorcamera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

object PermissionsChecker {

    const val CAMERA_REQUEST_CODE = 1001
    const val STORAGE_REQUEST_CODE = 1002

    // Permissions for read/write access to external storage are not required because
    // we're only using private app storage.

    private fun hasPermission(activity: Activity, perm: String): Boolean {
        return activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    // Permissions needed to read images saved by other camera apps and automatically convert them.
    // Android API 33 needs READ_MEDIA_IMAGES, earlier versions need READ_EXTERNAL_STORAGE.
    // See https://developer.android.com/reference/android/Manifest.permission#READ_MEDIA_IMAGES
    private fun storagePermissions() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasCameraPermission(activity: Activity): Boolean {
        return hasPermission(activity, Manifest.permission.CAMERA)
    }

    fun hasMicrophonePermission(activity: Activity): Boolean {
        return hasPermission(activity, Manifest.permission.RECORD_AUDIO)
    }

    fun hasStoragePermissions(activity: Activity): Boolean {
        return storagePermissions().all {hasPermission(activity, it)}
    }

    fun requestCameraPermissions(activity: Activity) {
        activity.requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                CAMERA_REQUEST_CODE)
    }

    fun requestStoragePermissions(activity: Activity) {
        activity.requestPermissions(storagePermissions(), STORAGE_REQUEST_CODE)
    }
}
