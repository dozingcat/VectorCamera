package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import com.dozingcatsoftware.vectorcamera.CameraImage
import kotlin.math.roundToInt

/**
 * Pure Kotlin implementation of EdgeLuminanceEffect.
 * Preserves the "color" of each pixel as given by its U and V values, but replaces its
 * brightness (Y value) with its edge strength.
 */
class EdgeLuminanceEffectKotlin : Effect {

    override fun effectName() = EFFECT_NAME

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val width = cameraImage.width()
        val height = cameraImage.height()
        val multiplier = minOf(4, maxOf(2, Math.round(width / 480f)))

        // Get YUV data directly from CameraImage
        val yuvBytes = cameraImage.getYuvBytes()
        if (yuvBytes != null) {
            // Use direct YUV bytes from ImageData
            return createBitmapFromYuvBytes(yuvBytes, width, height, multiplier)
        } else {
            // Fallback to RenderScript allocations if needed
            val planarYuv = cameraImage.getPlanarYuvAllocations()!!
            return createBitmapFromAllocations(planarYuv, width, height, multiplier)
        }
    }

    private fun createBitmapFromYuvBytes(yuvBytes: ByteArray, width: Int, height: Int, multiplier: Int): Bitmap {
        val ySize = width * height
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val uvSize = uvWidth * uvHeight

        // Extract planes from the flattened YUV bytes
        val yData = yuvBytes.sliceArray(0 until ySize)
        val uData = yuvBytes.sliceArray(ySize until ySize + uvSize)
        val vData = yuvBytes.sliceArray(ySize + uvSize until ySize + 2 * uvSize)

        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x

                // Calculate edge strength using Laplacian operator
                val edgeStrength = if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                    val center = yData[pixelIndex].toInt() and 0xFF
                    val surroundingSum = 
                        (yData[(y - 1) * width + (x - 1)].toInt() and 0xFF) +
                        (yData[(y - 1) * width + x].toInt() and 0xFF) +
                        (yData[(y - 1) * width + (x + 1)].toInt() and 0xFF) +
                        (yData[y * width + (x - 1)].toInt() and 0xFF) +
                        (yData[y * width + (x + 1)].toInt() and 0xFF) +
                        (yData[(y + 1) * width + (x - 1)].toInt() and 0xFF) +
                        (yData[(y + 1) * width + x].toInt() and 0xFF) +
                        (yData[(y + 1) * width + (x + 1)].toInt() and 0xFF)
                    
                    val edge = 8 * center - surroundingSum
                    (multiplier * edge).coerceIn(0, 255)
                } else {
                    0
                }

                // Get U and V values (subsampled)
                val uvX = x / 2
                val uvY = y / 2
                val uvIndex = uvY * uvWidth + uvX
                val u = uData[uvIndex].toInt() and 0xFF
                val v = vData[uvIndex].toInt() and 0xFF

                // Convert YUV to RGB using edge strength as Y value
                val rgb = yuvToRgb(edgeStrength, u, v)
                pixels[pixelIndex] = rgb
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun createBitmapFromAllocations(planarYuv: com.dozingcatsoftware.vectorcamera.PlanarYuvAllocations, 
                                           width: Int, height: Int, multiplier: Int): Bitmap {
        // Extract data from RenderScript allocations
        val ySize = width * height
        val uvWidth = width / 2
        val uvHeight = height / 2
        val uvSize = uvWidth * uvHeight

        val yData = ByteArray(ySize)
        val uData = ByteArray(uvSize)
        val vData = ByteArray(uvSize)

        planarYuv.y.copyTo(yData)
        planarYuv.u.copyTo(uData)
        planarYuv.v.copyTo(vData)

        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x

                // Calculate edge strength using Laplacian operator
                val edgeStrength = if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                    val center = yData[pixelIndex].toInt() and 0xFF
                    val surroundingSum = 
                        (yData[(y - 1) * width + (x - 1)].toInt() and 0xFF) +
                        (yData[(y - 1) * width + x].toInt() and 0xFF) +
                        (yData[(y - 1) * width + (x + 1)].toInt() and 0xFF) +
                        (yData[y * width + (x - 1)].toInt() and 0xFF) +
                        (yData[y * width + (x + 1)].toInt() and 0xFF) +
                        (yData[(y + 1) * width + (x - 1)].toInt() and 0xFF) +
                        (yData[(y + 1) * width + x].toInt() and 0xFF) +
                        (yData[(y + 1) * width + (x + 1)].toInt() and 0xFF)
                    
                    val edge = 8 * center - surroundingSum
                    (multiplier * edge).coerceIn(0, 255)
                } else {
                    0
                }

                // Get U and V values (subsampled)
                val uvX = x / 2
                val uvY = y / 2
                val uvIndex = uvY * uvWidth + uvX
                val u = uData[uvIndex].toInt() and 0xFF
                val v = vData[uvIndex].toInt() and 0xFF

                // Convert YUV to RGB using edge strength as Y value
                val rgb = yuvToRgb(edgeStrength, u, v)
                pixels[pixelIndex] = rgb
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Convert YUV to RGB using standard conversion formulas.
     * Based on ITU-R BT.601 conversion equations.
     */
    private fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        val yValue = y.toFloat()
        val uValue = (u - 128).toFloat()
        val vValue = (v - 128).toFloat()

        val red = (yValue + 1.370705f * vValue).roundToInt().coerceIn(0, 255)
        val green = (yValue - 0.698001f * vValue - 0.337633f * uValue).roundToInt().coerceIn(0, 255)
        val blue = (yValue + 1.732446f * uValue).roundToInt().coerceIn(0, 255)

        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    companion object {
        const val EFFECT_NAME = "edge_luminance_kotlin"

        fun fromParameters(params: Map<String, Any>): EdgeLuminanceEffectKotlin {
            return EdgeLuminanceEffectKotlin()
        }
    }
} 