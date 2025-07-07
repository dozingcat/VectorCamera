package com.dozingcatsoftware.vectorcamera

import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicResize
import android.util.Size
import com.dozingcatsoftware.util.create2dAllocation
import java.nio.ByteBuffer

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
     * For single YUV allocations, returns the allocation directly.
     * For planar allocations, returns the Y (luminance) plane.
     * For ImageData, creates and caches a single YUV allocation.
     */
    fun getPrimaryYuvAllocation(): Allocation {
        return when {
            _singleYuvAllocation != null -> _singleYuvAllocation
            _planarYuvAllocations != null -> _planarYuvAllocations.y
            else -> getSingleYuvAllocation()!!
        }
    }

    /**
     * Returns the single YUV allocation if available.
     * For ImageData, returns null as we don't create single YUV allocations.
     */
    fun getSingleYuvAllocation(): Allocation? {
        // Return existing allocation if available
        if (_singleYuvAllocation != null) return _singleYuvAllocation

        throw Exception("Creating single YUV allocation from image data not supported")
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
     * Returns true if this image uses planar YUV allocations (separate Y, U, V).
     */
    fun hasPlanarYuv(): Boolean {
        return _planarYuvAllocations != null || 
               (_imageData != null && _singleYuvAllocation == null)
    }

    /**
     * Returns true if this image uses a single YUV allocation.
     */
    fun hasSingleYuv(): Boolean {
        return _singleYuvAllocation != null || 
               (_imageData != null && _planarYuvAllocations == null)
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
