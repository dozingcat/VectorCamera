package com.dozingcatsoftware.vectorcamera

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

@TargetApi(23)
object PermissionsChecker {

    val CAMERA_AND_STORAGE_REQUEST_CODE = 1001
    val STORAGE_FOR_PHOTO_REQUEST_CODE = 1002
    val STORAGE_FOR_LIBRARY_REQUEST_CODE = 1003

    fun hasPermission(activity: Activity, perm: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCameraPermission(activity: Activity): Boolean {
        return hasPermission(activity, Manifest.permission.CAMERA)
    }

    fun hasMicrophonePermission(activity: Activity): Boolean {
        return hasPermission(activity, Manifest.permission.RECORD_AUDIO)
    }

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
