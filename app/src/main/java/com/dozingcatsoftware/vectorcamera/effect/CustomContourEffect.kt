package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Canvas
import android.graphics.RectF
import com.dozingcatsoftware.vectorcamera.CameraImage
import com.dozingcatsoftware.vectorcamera.CustomContourScheme
import com.dozingcatsoftware.vectorcamera.ProcessedBitmap

/**
 * Wraps a ContourEffect built from a user-editable CustomContourScheme, so that the scheme and
 * its preferences ID are available after the effect is selected.
 */
class CustomContourEffect(
        private val baseEffect: ContourEffect,
        val scheme: CustomContourScheme,
        val customSchemeId: String) : Effect {

    override fun effectName() = baseEffect.effectName()

    override fun effectParameters() = baseEffect.effectParameters()

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        baseEffect.drawBackground(cameraImage, canvas, rect)
    }

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val pb = baseEffect.createBitmap(cameraImage)
        return ProcessedBitmap(this, cameraImage, pb.bitmap, pb.metadata)
    }
}
