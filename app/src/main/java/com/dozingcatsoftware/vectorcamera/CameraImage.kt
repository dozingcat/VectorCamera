package com.dozingcatsoftware.vectorcamera

import android.media.Image
import android.util.Size
import kotlin.math.ceil

/**
 * Data structure that holds YUV image data extracted from Android's Image class.
 */
data class ImageData(
    val width: Int,
    val height: Int,
    val yData: ByteArray,
    val uData: ByteArray,
    val vData: ByteArray,
    val yPixelStride: Int,
    val yRowStride: Int,
    val uvPixelStride: Int,
    val uvRowStride: Int
) {
    companion object {
        fun fromImage(image: Image): ImageData {
            val planes = image.planes
            if (planes.size != 3) {
                throw IllegalStateException("Expected 3 planes for YUV_420_888, got ${planes.size}")
            }

            // Extract Y plane
            val yPlane = planes[0]
            val yBuffer = yPlane.buffer
            val ySize = yBuffer.remaining()
            val yData = ByteArray(ySize)
            yBuffer.get(yData)

            // Extract U plane  
            val uPlane = planes[1]
            val uBuffer = uPlane.buffer
            val uSize = uBuffer.remaining()
            val uData = ByteArray(uSize)
            uBuffer.get(uData)

            // Extract V plane
            val vPlane = planes[2]
            val vBuffer = vPlane.buffer
            val vSize = vBuffer.remaining()
            val vData = ByteArray(vSize)
            vBuffer.get(vData)

            return ImageData(
                width = image.width,
                height = image.height,
                yData = yData,
                uData = uData,
                vData = vData,
                yPixelStride = yPlane.pixelStride,
                yRowStride = yPlane.rowStride,
                uvPixelStride = uPlane.pixelStride,
                uvRowStride = uPlane.rowStride
            )
        }

        fun fromYuvBytes(yuvBytes: ByteArray, width: Int, height: Int): ImageData {
            val uvWidth = ceil(width / 2.0).toInt()
            val uvHeight = ceil(height / 2.0).toInt()
            val uStart = width * height
            val vStart = uStart + (uvWidth * uvHeight)

            return ImageData(
                width = width,
                height = height,
                yData = yuvBytes.copyOfRange(0, uStart),
                uData = yuvBytes.copyOfRange(uStart, vStart),
                vData = yuvBytes.copyOfRange(vStart, yuvBytes.size),
                yPixelStride = 1,
                yRowStride = width,
                uvPixelStride = 1,
                uvRowStride = uvWidth
            )
        }
    }
}

/**
 * An image to be processed, which comes directly from camera input or from an existing image.
 */
data class CameraImage(
    private val imageData: ImageData,
    val orientation: ImageOrientation, 
    val status: CameraStatus,
    val timestamp: Long, 
    val displaySize: Size = zeroSize
) {

    // width() and height() return the dimensions of the actual camera input, which is always
    // landscape. In portrait orientation the rendered image will have the dimensions swapped.
    fun width(): Int = imageData.width
    fun height(): Int = imageData.height
    fun size() = Size(width(), height())

    /**
     * Returns YUV bytes directly from ImageData.
     * This is the primary method used by all Kotlin/C++ effects.
     */
    fun getYuvBytes(): ByteArray {
        val yBytes = getYBytes()
        val uBytes = getUBytes()
        val vBytes = getVBytes()

        val outputBytes = ByteArray(yBytes.size + uBytes.size + vBytes.size)
        System.arraycopy(yBytes, 0, outputBytes, 0, yBytes.size)
        System.arraycopy(uBytes, 0, outputBytes, yBytes.size, uBytes.size)
        System.arraycopy(vBytes, 0, outputBytes, yBytes.size + uBytes.size, vBytes.size)
        return outputBytes
    }

    /**
     * Extracts Y plane bytes, handling pixel stride and row stride.
     */
    fun getYBytes(): ByteArray {
        val width = imageData.width
        val height = imageData.height
        val yData = imageData.yData
        val pixelStride = imageData.yPixelStride
        val rowStride = imageData.yRowStride
        
        if (pixelStride == 1 && rowStride == width) {
            // Densely packed, can use directly.
            return yData
        }
        
        // Need to extract with stride
        val outputBytes = ByteArray(width * height)
        var outputIndex = 0
        
        for (row in 0 until height) {
            var inputIndex = row * rowStride
            for (col in 0 until width) {
                outputBytes[outputIndex++] = yData[inputIndex]
                inputIndex += pixelStride
            }
        }
        
        return outputBytes
    }

    fun getUBytes(): ByteArray = extractUvPlaneBytes(imageData.uData)
    fun getVBytes(): ByteArray = extractUvPlaneBytes(imageData.vData)

    /**
     * Extracts U or V plane bytes, handling pixel stride and row stride.
     */
    private fun extractUvPlaneBytes(uOrVData: ByteArray): ByteArray {
        val width = imageData.width
        val height = imageData.height
        val uvWidth = (width + 1) / 2  // Round up for odd dimensions
        val uvHeight = (height + 1) / 2
        val uvPixelStride = imageData.uvPixelStride
        val uvRowStride = imageData.uvRowStride

        if (imageData.uvPixelStride == 1 && imageData.uvRowStride == uvWidth) {
            // Densely packed, can use directly.
            return uOrVData
        }

        // Need to extract with stride
        val outputBytes = ByteArray(uvWidth * uvHeight)
        var outputIndex = 0
        
        for (row in 0 until uvHeight) {
            var inputIndex = row * uvRowStride
            for (col in 0 until uvWidth) {
                outputBytes[outputIndex++] = uOrVData[inputIndex]
                inputIndex += uvPixelStride
            }
        }
        
        return outputBytes
    }

    /**
     * Creates a resized copy of this CameraImage using pure Kotlin bilinear interpolation.
     */
    fun resizedTo(size: Size): CameraImage {
        // If no resizing needed, return copy
        if (size.width == width() && size.height == height()) {
            return this
        }
        
        // Resize the ImageData using direct YUV byte manipulation
        val resizedImageData = resizeImageData(imageData, size.width, size.height)
        
        return copy(imageData = resizedImageData)
    }

    /**
     * Resizes ImageData using bilinear interpolation on YUV byte arrays.
     */
    private fun resizeImageData(imageData: ImageData, newWidth: Int, newHeight: Int): ImageData {
        val yuvBytes = getYuvBytes()
        val resizedYuvBytes = resizeYuvBytes(yuvBytes, imageData.width, imageData.height, 
                                           newWidth, newHeight)
        return ImageData.fromYuvBytes(resizedYuvBytes, newWidth, newHeight)
    }

    /**
     * Resizes YUV byte array using bilinear interpolation.
     */
    private fun resizeYuvBytes(yuvBytes: ByteArray, oldWidth: Int, oldHeight: Int, 
                              newWidth: Int, newHeight: Int): ByteArray {
        val oldYSize = oldWidth * oldHeight
        val oldUvWidth = (oldWidth + 1) / 2
        val oldUvHeight = (oldHeight + 1) / 2
        val oldUvSize = oldUvWidth * oldUvHeight
        
        val newYSize = newWidth * newHeight
        val newUvWidth = (newWidth + 1) / 2
        val newUvHeight = (newHeight + 1) / 2
        val newUvSize = newUvWidth * newUvHeight
        
        val result = ByteArray(newYSize + 2 * newUvSize)
        
        // Resize Y plane
        val oldYData = yuvBytes.sliceArray(0 until oldYSize)
        val newYData = resizePlane(oldYData, oldWidth, oldHeight, newWidth, newHeight)
        System.arraycopy(newYData, 0, result, 0, newYSize)
        
        // Resize U plane
        val oldUData = yuvBytes.sliceArray(oldYSize until oldYSize + oldUvSize)
        val newUData = resizePlane(oldUData, oldUvWidth, oldUvHeight, newUvWidth, newUvHeight)
        System.arraycopy(newUData, 0, result, newYSize, newUvSize)
        
        // Resize V plane
        val oldVData = yuvBytes.sliceArray(oldYSize + oldUvSize until oldYSize + 2 * oldUvSize)
        val newVData = resizePlane(oldVData, oldUvWidth, oldUvHeight, newUvWidth, newUvHeight)
        System.arraycopy(newVData, 0, result, newYSize + newUvSize, newUvSize)
        
        return result
    }

    /**
     * Resizes a single plane using bilinear interpolation.
     */
    private fun resizePlane(data: ByteArray, oldWidth: Int, oldHeight: Int, 
                           newWidth: Int, newHeight: Int): ByteArray {
        val result = ByteArray(newWidth * newHeight)
        val xRatio = oldWidth.toFloat() / newWidth
        val yRatio = oldHeight.toFloat() / newHeight
        
        for (y in 0 until newHeight) {
            for (x in 0 until newWidth) {
                val px = x * xRatio
                val py = y * yRatio
                
                val x1 = px.toInt()
                val y1 = py.toInt()
                val x2 = kotlin.math.min(x1 + 1, oldWidth - 1)
                val y2 = kotlin.math.min(y1 + 1, oldHeight - 1)
                
                val fx = px - x1
                val fy = py - y1
                
                val p1 = data[y1 * oldWidth + x1].toInt() and 0xFF
                val p2 = data[y1 * oldWidth + x2].toInt() and 0xFF
                val p3 = data[y2 * oldWidth + x1].toInt() and 0xFF
                val p4 = data[y2 * oldWidth + x2].toInt() and 0xFF
                
                val interpolated = (p1 * (1 - fx) * (1 - fy) +
                                  p2 * fx * (1 - fy) +
                                  p3 * (1 - fx) * fy +
                                  p4 * fx * fy).toInt()
                
                result[y * newWidth + x] = interpolated.toByte()
            }
        }
        
        return result
    }

    companion object {
        val zeroSize = Size(0, 0)

        /**
         * Creates a CameraImage from an Android Image (from ImageReader).
         */
        fun fromImage(image: Image, orientation: ImageOrientation, status: CameraStatus, 
                     timestamp: Long, displaySize: Size = zeroSize): CameraImage {
            val imageData = ImageData.fromImage(image)
            return CameraImage(imageData, orientation, status, timestamp, displaySize)
        }

        /**
         * Creates a CameraImage from YUV bytes.
         */
        fun fromYuvBytes(yuvBytes: ByteArray, width: Int, height: Int, 
                        orientation: ImageOrientation, status: CameraStatus,
                        timestamp: Long, displaySize: Size = zeroSize): CameraImage {
            val imageData = ImageData.fromYuvBytes(yuvBytes, width, height)
            return CameraImage(imageData, orientation, status, timestamp, displaySize)
        }
    }
}
