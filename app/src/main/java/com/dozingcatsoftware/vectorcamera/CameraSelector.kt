package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata



enum class ImageSize {
    FULL_SCREEN,
    HALF_SCREEN,
    VIDEO_RECORDING,
    EFFECT_GRID,
}

class CameraSelector(val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraIds = cameraManager.cameraIdList
    val cameraCount = cameraIds.size
    var selectedCameraIndex = 0

    fun selectNextCamera() {
        selectedCameraIndex = (1 + selectedCameraIndex) % cameraCount
    }

    fun createImageGenerator(): CameraImageGenerator {
        return CameraImageGenerator(context, cameraManager, cameraIds[selectedCameraIndex])
    }

    fun isSelectedCameraFrontFacing(): Boolean {
        var cc = cameraManager.getCameraCharacteristics(cameraIds[selectedCameraIndex])
        return cc.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
    }
}
