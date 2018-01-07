package com.dozingcatsoftware.boojiecam.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.renderscript.RenderScript
import android.util.Size
import com.dozingcatsoftware.boojiecam.CameraImage

/**
 * Created by brian on 12/17/17.
 */
class CombinationEffect(
        private val rs: RenderScript,
        private val effectFactories: List<(RenderScript) -> Effect>): Effect {

    override fun effectName() = "combination"

    override fun createBitmap(originalCameraImage: CameraImage): Bitmap {
        val gridSize = Math.floor(Math.sqrt(effectFactories.size.toDouble())).toInt()
        val cameraImage = originalCameraImage.withDisplaySize(Size(
                originalCameraImage.displaySize.width / gridSize,
                originalCameraImage.displaySize.height / gridSize))

        val outputWidth = cameraImage.displaySize.width
        val outputHeight = cameraImage.displaySize.height
        val tileWidth = outputWidth / gridSize
        val tileHeight = outputHeight / gridSize
        val tileBuffer = Bitmap.createBitmap(
                outputWidth / gridSize, outputHeight / gridSize, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(tileBuffer)

        val resultBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val resultCanvas = Canvas(resultBitmap)

        val srcRect = RectF(0f, 0f, tileBuffer.width.toFloat(), tileBuffer.height.toFloat())
        for (i in 0 until effectFactories.size) {
            val effect = effectFactories[i](rs)
            val tileBitmap = effect.createBitmap(cameraImage)
            val tileBitmapRect = Rect(0, 0, tileBitmap.width, tileBitmap.height)
            val tilePaint = effect.createPaintFn(cameraImage)(srcRect)
            if (tilePaint != null) {
                tileCanvas.drawRect(srcRect, tilePaint)
            }
            tileCanvas.drawBitmap(tileBitmap, tileBitmapRect, srcRect, null)

            var gridX = i % gridSize
            var gridY = i / gridSize
            if (cameraImage.orientation.isXFlipped()) {
                gridX = gridSize - 1 - gridX
            }
            if (cameraImage.orientation.isYFlipped()) {
                gridY = gridSize - 1 - gridY
            }
            val dstRect = Rect(gridX * tileWidth, gridY * tileHeight,
                    (gridX + 1) * tileWidth, (gridY + 1) * tileHeight)
            resultCanvas.drawBitmap(tileBuffer, null, dstRect, null)
        }

        return resultBitmap
    }
}
