package com.dozingcatsoftware.vectorcamera

import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicResize
import android.util.Size
import com.dozingcatsoftware.util.create2dAllocation
import kotlin.math.ceil

/**
 * Stores YUV image data extracted from an Android Image.
 * This allows us to immediately close the Image and free up the ImageReader buffer.
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
 * An image to be processed, which can come directly from a camera input or from an existing image.
 * The image data can be either:
 * - Raw ImageData extracted from ImageReader (modern approach)
 * - YUV allocation in `_singleYuvAllocation` (legacy RenderScript)
 * - Separate Y/U/V allocations in `_planarYuvAllocations` (legacy RenderScript)
 */
data class CameraImage(val rs: RenderScript,
                       private val _singleYuvAllocation: Allocation?,
                       private val _planarYuvAllocations: PlanarYuvAllocations?,
                       private val _imageData: ImageData?,
                       val orientation: ImageOrientation, val status: CameraStatus,
                       val timestamp: Long, val displaySize: Size = zeroSize) {

    // Lazy allocation creation - these will be created when first accessed
    private var _lazySingleYuvAllocation: Allocation? = null
    private var _lazyPlanarYuvAllocations: PlanarYuvAllocations? = null

    // width() and height() return the dimensions of the actual camera input, which is always
    // landscape. In portrait orientation the rendered image will have the dimensioned swapped.
    fun width(): Int {
        return when {
            _singleYuvAllocation != null -> _singleYuvAllocation.type.x
            _planarYuvAllocations != null -> _planarYuvAllocations.y.type.x
            _imageData != null -> _imageData.width
            else -> throw IllegalStateException("No image data available")
        }
    }

    fun height(): Int {
        return when {
            _singleYuvAllocation != null -> _singleYuvAllocation.type.y
            _planarYuvAllocations != null -> _planarYuvAllocations.y.type.y
            _imageData != null -> _imageData.height
            else -> throw IllegalStateException("No image data available")
        }
    }

    fun size() = Size(width(), height())

    /**
     * Returns the primary YUV allocation for RenderScript effects.
     * Always returns the Y (luminance) plane from planar allocations.
     */
    fun getPrimaryYuvAllocation(): Allocation {
        return when {
            _planarYuvAllocations != null -> _planarYuvAllocations.y
            _singleYuvAllocation != null -> _singleYuvAllocation
            else -> getPlanarYuvAllocations()!!.y
        }
    }

    /**
     * Returns YUV bytes directly from ImageData or RenderScript allocation.
     * This is used for saving images without needing to create complex allocations.
     */
    fun getYuvBytes(): ByteArray? {
        if (_imageData != null) {
            return extractYuvBytesFromImageData(_imageData)
        }
        else if (_singleYuvAllocation != null) {
            // For legacy RenderScript allocations, we need to use the flatten_yuv script
            return null // Let the caller handle this with flattenedYuvImageBytes
        }
        return null
    }

    /**
     * Extracts YUV bytes from ImageData in the same format as flattenedYuvImageBytes.
     */
    private fun extractYuvBytesFromImageData(imageData: ImageData): ByteArray {
        val width = imageData.width
        val height = imageData.height
        val ySize = width * height
        val uvWidth = (width + 1) / 2  // Round up for odd dimensions
        val uvHeight = (height + 1) / 2
        val uvSize = uvWidth * uvHeight
        
        val outputBytes = ByteArray(ySize + 2 * uvSize)
        
        // Extract Y plane
        val yBytes = extractYPlaneBytes(imageData)
        System.arraycopy(yBytes, 0, outputBytes, 0, ySize)
        
        // Extract U plane
        val uBytes = extractUvPlaneBytes(imageData.uData, imageData.uvPixelStride, 
                                        imageData.uvRowStride, uvWidth, uvHeight)
        System.arraycopy(uBytes, 0, outputBytes, ySize, uvSize)
        
        // Extract V plane
        val vBytes = extractUvPlaneBytes(imageData.vData, imageData.uvPixelStride, 
                                        imageData.uvRowStride, uvWidth, uvHeight)
        System.arraycopy(vBytes, 0, outputBytes, ySize + uvSize, uvSize)
        
        return outputBytes
    }

    /**
     * Returns the planar YUV allocations if available, creating them from ImageData if needed.
     */
    fun getPlanarYuvAllocations(): PlanarYuvAllocations? {
        // Return existing planar allocations if available
        if (_planarYuvAllocations != null) return _planarYuvAllocations
        
        // Return cached lazy allocations if available
        if (_lazyPlanarYuvAllocations != null) return _lazyPlanarYuvAllocations
        
        // Create from ImageData if available
        if (_imageData != null) {
            _lazyPlanarYuvAllocations = createPlanarAllocationsFromImageData()
            return _lazyPlanarYuvAllocations
        }
        
        return null
    }

    /**
     * Creates a single YUV allocation from the ImageData.
     * This creates a properly formatted YUV allocation that RenderScript can work with.
     */
    private fun createAllocationFromImageData(): Allocation {
        if (_imageData == null) {
            throw IllegalStateException("No ImageData available")
        }

        // For now, create planar allocations and return the Y plane
        // This is a fallback - ideally we'd use planar allocations for better performance
        val planar = createPlanarAllocationsFromImageData()
        return planar.y
    }

    /**
     * Copies ImageData to a YUV allocation in the format expected by RenderScript.
     */
    private fun copyImageDataToYuvAllocation(imageData: ImageData, yuvAlloc: Allocation) {
        // For proper YUV allocation creation, we need to create a RenderScript that
        // can handle the conversion from separate Y, U, V planes to a single YUV allocation.
        // For now, let's use the planar approach and let the flatten_yuv script handle it.
        
        // Actually, let's take a different approach - avoid the single YUV allocation entirely
        // and modify the saving process to work with planar allocations
        throw UnsupportedOperationException("Single YUV allocation creation not yet implemented")
    }

    /**
     * Creates separate Y, U, V allocations from the ImageData.
     */
    private fun createPlanarAllocationsFromImageData(): PlanarYuvAllocations {
        if (_imageData == null) {
            throw IllegalStateException("No ImageData available")
        }

        // Extract Y plane data
        val yBytes = extractYPlaneBytes(_imageData)
        val yAlloc = create2dAllocation(rs, Element::U8, width(), height())
        yAlloc.copyFrom(yBytes)

        // Extract U and V plane data (half resolution)
        val uvWidth = width() / 2
        val uvHeight = height() / 2
        
        val uBytes = extractUvPlaneBytes(_imageData.uData, _imageData.uvPixelStride, 
                                        _imageData.uvRowStride, uvWidth, uvHeight)
        val uAlloc = create2dAllocation(rs, Element::U8, uvWidth, uvHeight)
        uAlloc.copyFrom(uBytes)

        val vBytes = extractUvPlaneBytes(_imageData.vData, _imageData.uvPixelStride, 
                                        _imageData.uvRowStride, uvWidth, uvHeight)
        val vAlloc = create2dAllocation(rs, Element::U8, uvWidth, uvHeight)
        vAlloc.copyFrom(vBytes)

        return PlanarYuvAllocations(yAlloc, uAlloc, vAlloc)
    }

    /**
     * Extracts Y plane bytes, handling potential row stride.
     */
    private fun extractYPlaneBytes(imageData: ImageData): ByteArray {
        val width = imageData.width
        val height = imageData.height
        val pixelStride = imageData.yPixelStride
        val rowStride = imageData.yRowStride
        val data = imageData.yData
        
        if (pixelStride == 1 && rowStride == width) {
            // Contiguous data - can use directly
            return data.copyOf(width * height)
        } else {
            // Need to extract with stride
            val extractedBytes = ByteArray(width * height)
            var srcPos = 0
            var dstPos = 0
            
            for (row in 0 until height) {
                for (col in 0 until width) {
                    extractedBytes[dstPos++] = data[srcPos]
                    srcPos += pixelStride
                }
                // Move to start of next row
                srcPos = (row + 1) * rowStride
            }
            
            return extractedBytes
        }
    }

    /**
     * Extracts UV plane bytes, handling potential pixel stride and row stride.
     */
    private fun extractUvPlaneBytes(data: ByteArray, pixelStride: Int, rowStride: Int, 
                                   width: Int, height: Int): ByteArray {
        val size = width * height
        val bytes = ByteArray(size)
        
        if (pixelStride == 1 && rowStride == width) {
            // Contiguous data - can copy directly
            System.arraycopy(data, 0, bytes, 0, size)
        } else {
            // Interleaved data - need to extract with stride
            var srcPos = 0
            var dstPos = 0
            for (row in 0 until height) {
                for (col in 0 until width) {
                    bytes[dstPos++] = data[srcPos]
                    srcPos += pixelStride
                }
                // Move to start of next row
                srcPos = (row + 1) * rowStride
            }
        }
        
        return bytes
    }

    /**
     * Clean up resources when the image is no longer needed.
     * Note: ImageData doesn't need cleanup as the original Image was already closed.
     */
    fun close() {
        // ImageData doesn't need cleanup - the original Image was closed immediately
        // This method is kept for compatibility
    }

    fun resizedTo(size: Size): CameraImage {
        // If no resizing needed, return copy
        if (size.width == width() && size.height == height()) {
            return this
        }
        
        // For RenderScript-only images that don't have ImageData, we need to preserve the old behavior
        // until RenderScript is completely removed
        if (_imageData == null) {
            val resizeScript = ScriptIntrinsicResize.create(rs)

            fun doResize(inputAlloc: Allocation, w: Int, h: Int): Allocation {
                val outputAlloc = create2dAllocation(rs, Element::U8, w, h)
                resizeScript.setInput(inputAlloc)
                resizeScript.forEach_bicubic(outputAlloc)
                return outputAlloc
            }

            return if (_singleYuvAllocation != null) {
                val outputAlloc = doResize(_singleYuvAllocation, size.width, size.height)
                copy(_singleYuvAllocation = outputAlloc, _imageData = null)
            }
            else {
                val planes = getPlanarYuvAllocations()!!
                val yOutput = doResize(planes.y, size.width, size.height)
                val uOutput = doResize(planes.u, size.width / 2, size.height / 2)
                val vOutput = doResize(planes.v, size.width / 2, size.height / 2)
                copy(_planarYuvAllocations = PlanarYuvAllocations(yOutput, uOutput, vOutput), _imageData = null)
            }
        }
        
        // For ImageData, resize the YUV byte arrays directly (RenderScript-free)
        val currentImageData = _imageData!!
        val newImageData = resizeImageData(currentImageData, size.width, size.height)

        return copy(_imageData = newImageData, _singleYuvAllocation = null, _planarYuvAllocations = null)
    }

    /**
     * Resize ImageData using direct YUV byte array manipulation (no RenderScript).
     * Uses bilinear interpolation for smooth resizing.
     */
    private fun resizeImageData(imageData: ImageData, newWidth: Int, newHeight: Int): ImageData {
        val srcWidth = imageData.width
        val srcHeight = imageData.height
        
        // Extract source Y/U/V planes
        val srcYBytes = extractYPlaneBytes(imageData)
        val srcUBytes = extractUvPlaneBytes(imageData.uData, imageData.uvPixelStride, 
                                           imageData.uvRowStride, srcWidth / 2, srcHeight / 2)
        val srcVBytes = extractUvPlaneBytes(imageData.vData, imageData.uvPixelStride, 
                                           imageData.uvRowStride, srcWidth / 2, srcHeight / 2)
        
        // Resize Y plane
        val newYBytes = resizeByteArray(srcYBytes, srcWidth, srcHeight, newWidth, newHeight)
        
        // Resize U and V planes (half resolution, round up for odd dimensions)
        val newUvWidth = (newWidth + 1) / 2
        val newUvHeight = (newHeight + 1) / 2
        val srcUvWidth = (srcWidth + 1) / 2
        val srcUvHeight = (srcHeight + 1) / 2
        
        val newUBytes = resizeByteArray(srcUBytes, srcUvWidth, srcUvHeight, newUvWidth, newUvHeight)
        val newVBytes = resizeByteArray(srcVBytes, srcUvWidth, srcUvHeight, newUvWidth, newUvHeight)
        
        return ImageData(
            width = newWidth,
            height = newHeight,
            yData = newYBytes,
            uData = newUBytes,
            vData = newVBytes,
            yPixelStride = 1,
            yRowStride = newWidth,
            uvPixelStride = 1,
            uvRowStride = newUvWidth
        )
    }

    /**
     * Resize a byte array representing a 2D image plane using bilinear interpolation.
     */
    private fun resizeByteArray(srcData: ByteArray, srcWidth: Int, srcHeight: Int, 
                               dstWidth: Int, dstHeight: Int): ByteArray {
        val dstData = ByteArray(dstWidth * dstHeight)
        
        val xScale = srcWidth.toFloat() / dstWidth
        val yScale = srcHeight.toFloat() / dstHeight
        
        for (dstY in 0 until dstHeight) {
            for (dstX in 0 until dstWidth) {
                // Calculate source coordinates
                val srcXf = dstX * xScale
                val srcYf = dstY * yScale
                
                // Get integer and fractional parts
                val srcX0 = srcXf.toInt().coerceIn(0, srcWidth - 1)
                val srcY0 = srcYf.toInt().coerceIn(0, srcHeight - 1)
                val srcX1 = (srcX0 + 1).coerceIn(0, srcWidth - 1)
                val srcY1 = (srcY0 + 1).coerceIn(0, srcHeight - 1)
                
                val fracX = srcXf - srcX0
                val fracY = srcYf - srcY0
                
                // Get four surrounding pixels
                val p00 = (srcData[srcY0 * srcWidth + srcX0].toInt() and 0xFF).toFloat()
                val p10 = (srcData[srcY0 * srcWidth + srcX1].toInt() and 0xFF).toFloat()
                val p01 = (srcData[srcY1 * srcWidth + srcX0].toInt() and 0xFF).toFloat()
                val p11 = (srcData[srcY1 * srcWidth + srcX1].toInt() and 0xFF).toFloat()
                
                // Bilinear interpolation
                val top = p00 + (p10 - p00) * fracX
                val bottom = p01 + (p11 - p01) * fracX
                val result = top + (bottom - top) * fracY
                
                dstData[dstY * dstWidth + dstX] = result.toInt().coerceIn(0, 255).toByte()
            }
        }
        
        return dstData
    }

    companion object {
        private val zeroSize = Size(0, 0)

        fun withAllocation(rs: RenderScript, allocation: Allocation, orientation: ImageOrientation,
                           status: CameraStatus, timestamp: Long,
                           displaySize: Size = zeroSize): CameraImage {
            return CameraImage(rs, allocation, null, null, orientation, status, timestamp, displaySize)
        }

        fun withAllocationSet(rs: RenderScript, yuv: PlanarYuvAllocations,
                              orientation: ImageOrientation, status: CameraStatus, timestamp: Long,
                              displaySize: Size = zeroSize): CameraImage {
            return CameraImage(rs, null, yuv, null, orientation, status, timestamp, displaySize)
        }

        fun withImageData(rs: RenderScript, imageData: ImageData, orientation: ImageOrientation,
                          status: CameraStatus, timestamp: Long,
                          displaySize: Size = zeroSize): CameraImage {
            return CameraImage(rs, null, null, imageData, orientation, status, timestamp, displaySize)
        }
    }
}
