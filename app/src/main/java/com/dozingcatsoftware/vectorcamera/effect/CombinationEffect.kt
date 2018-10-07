package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.util.Size
import com.dozingcatsoftware.vectorcamera.CameraImage

/**
 * Effect that takes a list of other effects and renders them all in a grid. Used to implement
 * the UI for selecting an effect.
 */
class CombinationEffect(
        private val effectFactories: List<() -> Effect>,
        private val maxMillisPerFrame: Long = Long.MAX_VALUE,
        private val timeFn: (() -> Long) = System::currentTimeMillis): Effect {

    override fun effectName() = "combination"

    var effectIndex = 0
    var resultBitmap: Bitmap? = null

    private fun getResultBitmap(width: Int, height: Int): Bitmap {
        var b = resultBitmap
        if (b == null || b.width != width || b.height != height) {
            b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            resultBitmap = b
        }
        return b!!
    }

    override fun createBitmap(originalCameraImage: CameraImage): Bitmap {
        val gridSize = Math.ceil(Math.sqrt(effectFactories.size.toDouble())).toInt()
        val tileSize = Size(
                originalCameraImage.displaySize.width / gridSize,
                originalCameraImage.displaySize.height / gridSize)
        val cameraImage = originalCameraImage.copy(displaySize=tileSize)
        // We're rendering several subeffects at low resolution; use the full display resolution
        // for the combined image.
        val outputWidth = originalCameraImage.displaySize.width
        val outputHeight = originalCameraImage.displaySize.height
        val tileWidth = outputWidth / gridSize
        val tileHeight = outputHeight / gridSize
        val tileBuffer = Bitmap.createBitmap(
                outputWidth / gridSize, outputHeight / gridSize, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(tileBuffer)

        val resultBitmap = getResultBitmap(outputWidth, outputHeight)
        val resultCanvas = Canvas(resultBitmap)

        val shouldRotate = cameraImage.orientation.portrait
        val srcRect = RectF(0f, 0f, tileBuffer.width.toFloat(), tileBuffer.height.toFloat())

        // Update as many subeffects as we can in the time limit specified by maxMillisPerFrame.
        val t0 = timeFn()
        var numUpdated = 0
        while (true) {
            val ei = effectIndex
            val effect = effectFactories[ei]()
            val tileBitmap = effect.createBitmap(cameraImage)
            val tileBitmapRect = Rect(0, 0, tileBitmap.width, tileBitmap.height)
            effect.drawBackground(cameraImage, tileCanvas, srcRect)
            tileCanvas.drawBitmap(tileBitmap, tileBitmapRect, srcRect, null)

            var gridX = if (shouldRotate) (ei / gridSize) else (ei % gridSize)
            var gridY = if (shouldRotate) (gridSize - 1 - ei % gridSize) else (ei / gridSize)
            // If the source camera image is flipped in the X and/or Y direction, we need to fill
            // the grid in a correspondingly flipped way, so that when the final grid image is drawn
            // it will end up in the correct orientation.
            if (cameraImage.orientation.xFlipped) {
                gridX = gridSize - 1 - gridX
            }
            if (cameraImage.orientation.yFlipped) {
                gridY = gridSize - 1 - gridY
            }
            val dstRect = Rect(gridX * tileWidth, gridY * tileHeight,
                    (gridX + 1) * tileWidth, (gridY + 1) * tileHeight)
            resultCanvas.drawBitmap(tileBuffer, null, dstRect, null)

            effectIndex = (effectIndex + 1) % effectFactories.size
            val t = timeFn()
            numUpdated += 1
            if (numUpdated >= effectFactories.size || t - t0 > maxMillisPerFrame) {
                break
            }
        }
        Log.i(TAG, "Combo time: ${System.currentTimeMillis() - t0}, updated: $numUpdated")
        return resultBitmap
    }

    companion object {
        const val TAG = "CombinationEffect"
    }
}
