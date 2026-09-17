package com.dozingcatsoftware.vectorcamera.effect

import com.dozingcatsoftware.vectorcamera.CameraImage
import com.dozingcatsoftware.vectorcamera.CustomPermuteScheme
import com.dozingcatsoftware.vectorcamera.ProcessedBitmap

/**
 * Wraps a PermuteColorEffect built from a user-editable CustomPermuteScheme, so that the scheme
 * and its preferences ID are available after the effect is selected.
 */
class CustomPermuteEffect(
        private val baseEffect: PermuteColorEffect,
        val scheme: CustomPermuteScheme,
        val customSchemeId: String) : Effect {

    override fun effectName() = baseEffect.effectName()

    override fun effectParameters() = baseEffect.effectParameters()

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val pb = baseEffect.createBitmap(cameraImage)
        return ProcessedBitmap(this, cameraImage, pb.bitmap, pb.metadata)
    }
}
