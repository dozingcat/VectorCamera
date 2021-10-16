package com.dozingcatsoftware.vectorcamera

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

object PermissionsChecker {

    const val CAMERA_AND_STORAGE_REQUEST_CODE = 1001
    const val STORAGE_FOR_PHOTO_REQUEST_CODE = 1002
    const val STORAGE_FOR_LIBRARY_REQUEST_CODE = 1003

    fun hasPermission(activity: Activity, perm: String): Boolean {
        return activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCameraPermission(activity: Activity): Boolean {
        return hasPermission(activity, Manifest.permission.CAMERA)
    }

    fun hasMicrophonePermission(activity: Activity): Boolean {
        return hasPermission(activity, Manifest.permission.RECORD_AUDIO)
    }

    // Storage permissions can probably be removed once all libraries are
    // moved to private storage.
    fun hasStoragePermission(activity: Activity): Boolean {
        return hasPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) &&
               hasPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun requestCameraAndStoragePermissions(activity: Activity) {
        activity.requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE),
                CAMERA_AND_STORAGE_REQUEST_CODE)
    }

    fun requestStoragePermissionsToTakePhoto(activity: Activity) {
        activity.requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_FOR_PHOTO_REQUEST_CODE)
    }

    fun requestStoragePermissionsToGoToLibrary(activity: Activity) {
        activity.requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_FOR_LIBRARY_REQUEST_CODE)
    }
}
