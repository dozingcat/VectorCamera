package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.renderscript.RenderScript
import com.dozingcatsoftware.vectorcamera.CameraImage

class MatrixEffect(val rs: RenderScript): Effect {

    override fun effectName() = EFFECT_NAME

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        return resultBitmap
    }

    companion object {
        const val EFFECT_NAME = "matrix"
    }
}