package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.renderscript.RenderScript
import android.util.Log
import android.util.Size
import com.dozingcatsoftware.vectorcamera.CameraImage

/**
 * Effect that takes a list of other effects and renders them all in a grid. Used to implement
 * the UI for selecting an effect.
 */
class CombinationEffect(
        private val effectFactories: List<() -> Effect>): Effect {

    override fun effectName() = "combination"

    override fun createBitmap(originalCameraImage: CameraImage): Bitmap {
        val gridSize = Math.ceil(Math.sqrt(effectFactories.size.toDouble())).toInt()
        val cameraImage = originalCameraImage.withDisplaySize(Size(
                originalCameraImage.displaySize.width / gridSize,
                originalCameraImage.displaySize.height / gridSize))
        // We're rendering several subeffects at low resolution; use the full display resolution
        // for the combined image.
        val outputWidth = originalCameraImage.displaySize.width
        val outputHeight = originalCameraImage.displaySize.height
        val tileWidth = outputWidth / gridSize
        val tileHeight = outputHeight / gridSize
        val tileBuffer = Bitmap.createBitmap(
                outputWidth / gridSize, outputHeight / gridSize, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(tileBuffer)

        val resultBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val resultCanvas = Canvas(resultBitmap)

        val shouldRotate = cameraImage.orientation.portrait
        val srcRect = RectF(0f, 0f, tileBuffer.width.toFloat(), tileBuffer.height.toFloat())

        val t0 = System.currentTimeMillis()
        for (i in 0 until effectFactories.size) {
            val t1 = System.currentTimeMillis()
            val effect = effectFactories[i]()
            val t2 = System.currentTimeMillis()
            val tileBitmap = effect.createBitmap(cameraImage)
            val t3 = System.currentTimeMillis()
            val tileBitmapRect = Rect(0, 0, tileBitmap.width, tileBitmap.height)
            effect.drawBackground(cameraImage, tileCanvas, srcRect)
            tileCanvas.drawBitmap(tileBitmap, tileBitmapRect, srcRect, null)

            var gridX = if (shouldRotate) (i / gridSize) else (i % gridSize)
            var gridY = if (shouldRotate) (gridSize - 1 - i % gridSize) else (i / gridSize)
            if (cameraImage.orientation.xFlipped) {
                gridX = gridSize - 1 - gridX
            }
            if (cameraImage.orientation.yFlipped) {
                gridY = gridSize - 1 - gridY
            }
            val dstRect = Rect(gridX * tileWidth, gridY * tileHeight,
                    (gridX + 1) * tileWidth, (gridY + 1) * tileHeight)
            resultCanvas.drawBitmap(tileBuffer, null, dstRect, null)
            val t4 = System.currentTimeMillis()
            // Log.i(TAG, "Combo grid timing: ${i} ${t2-t1} ${t3-t2} ${t4-t3}")
        }
        Log.i(TAG, "Combo total time: ${System.currentTimeMillis() - t0}")
        return resultBitmap
    }

    companion object {
        const val TAG = "CombinationEffect"
    }
}
