package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import com.dozingcatsoftware.vectorcamera.CameraImage
import com.dozingcatsoftware.vectorcamera.CustomColorScheme
import com.dozingcatsoftware.vectorcamera.ProcessedBitmap
import com.dozingcatsoftware.vectorcamera.ProcessedBitmapMetadata
import com.dozingcatsoftware.vectorcamera.CodeArchitecture

class CustomEffect(
        private val baseEffect: Effect,
        private val context: EffectContext,
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
        val bitmap = baseProcessedBitmap.bitmap
        // If in the combo grid, show "Custom" label. This is way more complicated than it should be
        // due to the combinations of landscape/portrait and X and Y rotations.
        if (context == EffectContext.COMBO_GRID) {
            val ds = cameraImage.size()
            val text = "Custom"
            val canvas = Canvas(bitmap)
            val p = Paint()
            p.style = Paint.Style.FILL
            p.textSize = ds.height / 2f
            p.color = Color.WHITE
            val maxWidth = 0.8f * (if (cameraImage.orientation.portrait) ds.height else ds.width)
            var textWidth = p.measureText(text)
            if (textWidth > maxWidth) {
                p.textSize /= (textWidth / maxWidth)
                textWidth = p.measureText(text)
            }
            // This is entirely trial and error; the emulator and a Nexus 5X hit all 8 cases.
            val xFlipped = cameraImage.orientation.xFlipped
            val yFlipped = cameraImage.orientation.yFlipped
            canvas.translate(ds.width / 2f, ds.height / 2f)
            if (cameraImage.orientation.portrait) {
                if (!xFlipped && !yFlipped) {
                    canvas.translate(p.textSize / 2, textWidth / 2)
                    canvas.rotate(-90f)
                }
                if (xFlipped && yFlipped) {
                    canvas.translate(-p.textSize / 2, -textWidth / 2)
                    canvas.rotate(90f)
                }
                if (xFlipped && !yFlipped) {
                    canvas.translate(-p.textSize / 2, textWidth / 2)
                    canvas.rotate(90f)
                    canvas.scale(-1f, 1f)
                }
                if (!xFlipped && yFlipped) {
                    canvas.translate(p.textSize / 2, -textWidth / 2)
                    canvas.rotate(-90f)
                    canvas.scale(-1f, 1f)
                }
            }
            else {
                if (!xFlipped && !yFlipped) {
                    canvas.translate(-textWidth / 2, p.textSize / 2)
                }
                if (xFlipped && yFlipped) {
                    canvas.translate(textWidth / 2, -p.textSize / 2)
                    canvas.rotate(180f)
                }
                if (xFlipped && !yFlipped) {
                    canvas.translate(textWidth / 2, p.textSize / 2)
                    canvas.scale(-1f, 1f)
                }
                if (!xFlipped && yFlipped) {
                    canvas.translate(-textWidth / 2, -p.textSize / 2)
                    canvas.scale(1f, -1f)
                }
            }
            canvas.drawText(text, 0f, 0f, p)
            p.color = Color.BLACK
            p.style = Paint.Style.STROKE
            canvas.drawText(text, 0f, 0f, p)
        }
        
        val endTime = System.nanoTime()
        val metadata = ProcessedBitmapMetadata(
            codeArchitecture = baseProcessedBitmap.metadata.codeArchitecture,
            numThreads = baseProcessedBitmap.metadata.numThreads,
            generationDurationNanos = baseProcessedBitmap.metadata.generationDurationNanos + (endTime - startTime)
        )
        
        return ProcessedBitmap(this, cameraImage, bitmap, metadata)
    }
}