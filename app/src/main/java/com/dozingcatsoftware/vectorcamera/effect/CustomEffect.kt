package com.dozingcatsoftware.vectorcamera.effect

import com.dozingcatsoftware.vectorcamera.CameraImage
import com.dozingcatsoftware.vectorcamera.ProcessedBitmap

/**
 * An effect built from a user-editable scheme (CustomColorScheme, CustomPermuteScheme, etc.),
 * wrapping the effect that does the rendering so that the scheme and its preferences ID are
 * available after the effect is selected. Callers tell the custom effects apart by the type
 * of `scheme`.
 */
class CustomEffect<S : Any>(
        private val baseEffect: Effect,
        val scheme: S,
        val customSchemeId: String) : Effect by baseEffect {

    // Bitmaps should refer to this effect rather than the base effect, so that the saved
    // metadata and the editor see the custom effect.
    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap =
            baseEffect.createBitmap(cameraImage).copy(effect = this)
}
