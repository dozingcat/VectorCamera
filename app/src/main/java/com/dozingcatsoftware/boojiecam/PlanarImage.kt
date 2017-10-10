package com.dozingcatsoftware.boojiecam

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

/**
 * Wrapper for android.media.image so we can create other implementations.
 */
interface PlanarImage {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int) {}

    val width: Int
    val height: Int
    val format: Int
    val planes: Array<Plane>

    fun close()

    companion object {
        fun fromMediaImage(image: Image): PlanarImage {
            return object: PlanarImage {
                override val width = image.width
                override val height = image.height
                override val format = image.format
                override val planes = image.planes.map(
                        {p -> Plane(p.buffer, p.rowStride, p.pixelStride)}).toTypedArray()

                override fun close() {
                    image.close()
                }
            }
        }

        fun fromFlattenedYuvBytes(bytes: ByteArray, width: Int, height: Int): PlanarImage {
            val numYPixels = width * height
            val uvWidth = width / 2
            val numUVPixels = uvWidth * (height / 2)
            return object: PlanarImage {
                override val width = width
                override val height = height
                override val format = ImageFormat.YUV_420_888
                override val planes = arrayOf(
                        Plane(ByteBuffer.wrap(bytes.copyOfRange(0, numYPixels)),
                                width, 1),
                        Plane(ByteBuffer.wrap(bytes.copyOfRange(numYPixels, numYPixels + numUVPixels)),
                                uvWidth, 1),
                        Plane(ByteBuffer.wrap(bytes.copyOfRange(numYPixels + numUVPixels, numYPixels + 2 * numUVPixels)),
                                uvWidth, 1))

                override fun close() {}
            }
        }
    }
}