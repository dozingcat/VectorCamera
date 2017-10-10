package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap

class GrayscaleImageGenerator: CameraImageProcessor() {

    override fun createBitmapFromImage(image: PlanarImage): Bitmap {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val brightness = getBufferBytes(image.planes[0].buffer)
        val rowStride = image.planes[0].rowStride
        val pixelStride = image.planes[0].pixelStride
        var pixelIndex = 0
        val pixels = IntArray(brightness.size)
        for (y in 0 until image.height) {
            var offset = y * rowStride
            for (x in 0 until image.width) {
                val b = brightness[offset].toInt() and 0xff
                val color = (0xFF shl 24) or (b shl 16) or (b shl 8) or b
                pixels[pixelIndex++] = color
                offset += pixelStride
            }
        }
        bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return bitmap
    }
}
