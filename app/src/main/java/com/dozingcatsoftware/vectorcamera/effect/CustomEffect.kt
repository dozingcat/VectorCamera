package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import com.dozingcatsoftware.vectorcamera.CameraImage
import com.dozingcatsoftware.vectorcamera.CustomColorScheme
import com.dozingcatsoftware.vectorcamera.ProcessedBitmap
import com.dozingcatsoftware.vectorcamera.ProcessedBitmapMetadata

/**
 * Wraps an effect built from a user-editable CustomColorScheme, so that the scheme and its
 * preferences ID are available after the effect is selected.
 */
class CustomEffect(
        private val baseEffect: Effect,
        val colorScheme: CustomColorScheme,
        val customSchemeId: String) : Effect {

    override fun effectName() = baseEffect.effectName()

    override fun effectParameters() = baseEffect.effectParameters()

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        baseEffect.drawBackground(cameraImage, canvas, rect)
    }

    override fun createBitmap(cameraImage: CameraImage): ProcessedBitmap {
        val startTime = System.nanoTime()
        val baseProcessedBitmap = baseEffect.createBitmap(cameraImage)
        val endTime = System.nanoTime()
        val metadata = ProcessedBitmapMetadata(
            codeArchitecture = baseProcessedBitmap.metadata.codeArchitecture,
            numThreads = baseProcessedBitmap.metadata.numThreads,
            generationDurationNanos = endTime - startTime
        )
        return ProcessedBitmap(this, cameraImage, baseProcessedBitmap.bitmap, metadata)
    }
}
