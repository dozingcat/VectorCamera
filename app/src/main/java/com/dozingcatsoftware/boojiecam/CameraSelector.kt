package com.dozingcatsoftware.boojiecam

import android.content.Context
import android.hardware.camera2.CameraManager
import android.renderscript.RenderScript


enum class ImageSize {
    FULL_SCREEN,
    HALF_SCREEN,
    VIDEO_RECORDING,
    EFFECT_GRID,
}

/**
 * Created by brian on 9/30/17.
 */
class CameraSelector(val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraIds = cameraManager.cameraIdList
    val cameraCount = cameraIds.size
    var selectedCameraIndex = 0

    fun selectNextCamera() {
        selectedCameraIndex = (1 + selectedCameraIndex) % cameraCount
    }

    fun createImageGenerator(rs: RenderScript): CameraImageGenerator {
        return CameraImageGenerator(context, rs, cameraManager, cameraIds[selectedCameraIndex])
    }
}