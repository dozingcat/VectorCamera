package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.renderscript.RenderScript
import android.util.Log
import android.util.Size

class CameraImageGenerator(val context: Context, val rs: RenderScript,
                           val cameraManager: CameraManager, val cameraId: String,
                           val timestampFn: () -> Long = System::currentTimeMillis) {


    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureSize: Size? = null
    private var zoomRatio = 0.0
    var status = CameraStatus.CLOSED
    private var targetStatus = CameraStatus.CLOSED
    private var imageAllocationCallback: ((CameraImage) -> Unit)? = null
    private var cameraClosedCallback: (() -> Unit)? = null
    private var handler = Handler()

    private val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

    private var imageReader: ImageReader? = null

    // https://stackoverflow.com/questions/33902832/upside-down-camera-preview-byte-array
    // https://www.reddit.com/r/Android/comments/3rjbo8/nexus5x_marshmallow_camera_problem/cwqzqgh
    private val imageOrientation = {
        val facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
        val isFrontFacing = (facing == CameraMetadata.LENS_FACING_FRONT)
        val orientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val upsideDown =
                (isFrontFacing && orientation == 90) || (!isFrontFacing && orientation == 270)
        // X flipped if the camera front facing or the orientation is backwards, but not both.
        Log.i(TAG, "isFrontFacing: ${isFrontFacing} upsideDown: ${upsideDown}")
        ImageOrientation(xFlipped = (isFrontFacing != upsideDown), yFlipped = upsideDown)
    }()

    fun start(targetStatus: CameraStatus, targetSize: Size,
              imageAllocationCallback: ((CameraImage) -> Unit)? = null) {
        Log.i(TAG, "start(), status=${status}")
        this.captureSize = pickBestSize(
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                        .getOutputSizes(ImageFormat.YUV_420_888),
                targetSize)
        this.targetStatus = targetStatus
        this.imageAllocationCallback = imageAllocationCallback

        if (this.status.isCapturing()) {
            Log.i(TAG, "Restarting capture")
            captureSession!!.abortCaptures()
            captureSession!!.close()
            this.status = CameraStatus.RESTARTING_CAPTURE
        }
        else {
            updateStatus(this.status)
        }
    }

    /**
     * Stops the camera and invokes the callback after the camera is completely closed. This avoids
     * errors that can occur when trying to open the camera while it's in the process of closing.
     */
    fun stop(closedCallback: (() -> Unit)? = null) {
        this.targetStatus = CameraStatus.CLOSED
        this.cameraClosedCallback = closedCallback
        updateStatus(this.status)
    }

    private fun updateStatus(status: CameraStatus) {
        Log.i(TAG, "CameraStatus change: ${status}, target=${targetStatus}")
        this.status = status

        if (this.targetStatus == CameraStatus.CLOSED) {
            if (this.status.isCapturing()) {
                this.status = CameraStatus.CLOSING
                stopCamera()
            }
        }
        else if (this.targetStatus.isCapturing()) {
            when (this.status) {
                // The status never actually gets set to CLOSED?
                CameraStatus.CLOSED,
                CameraStatus.CLOSING -> {
                    openCamera()
                }
                CameraStatus.OPENED -> {
                    setupCameraPreview()
                }
                CameraStatus.CAPTURE_READY -> {
                    startCapture()
                }
                CameraStatus.ERROR -> {
                    Log.i(TAG, "Trying to recover from error")
                    handler.postDelayed(this::openCamera, 1000)
                }
                else -> {

                }
            }
        }
    }

    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        stopCamera()
        try {
            updateStatus(CameraStatus.OPENING)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    Log.i(TAG, "camera onOpened")
                    camera = cam
                    updateStatus(CameraStatus.OPENED)
                }

                override fun onClosed(cam: CameraDevice) {
                    Log.i(TAG, "camera onClosed")
                    updateStatus(CameraStatus.CLOSED)
                    cameraClosedCallback?.invoke()
                }

                override fun onDisconnected(cam: CameraDevice) {
                    Log.i(TAG, "onDisconnected")
                    cam.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    updateStatus(CameraStatus.ERROR)
                }
            }, null)
        }
        catch (ex: SecurityException) {
            throw ex
        }
    }

    private fun setupCameraPreview() {
        try {
            val size = this.captureSize!!
            Log.i(TAG, "Using camera size: ${size}")

            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
            imageReader!!.setOnImageAvailableListener({
                try {
                    val image = it.acquireLatestImage()
                    if (image != null) {
                        if (captureSession != null) {
                            // Extract image data immediately and close the image to free the buffer
                            val imageData = ImageData.fromImage(image)
                            image.close()
                            
                            this.imageAllocationCallback?.invoke(CameraImage.withImageData(
                                        rs, imageData, imageOrientation, status, timestampFn()))
                        }
                        else {
                            Log.i(TAG, "captureSession is null, closing image")
                            image.close()
                        }
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Failed to acquire image: ${e.message}")
                    // Try to close any remaining images to free up the buffer
                    try {
                        val image = it.acquireNextImage()
                        image?.close()
                    } catch (cleanupException: Exception) {
                        Log.d(TAG, "No images to clean up")
                    }
                }
            }, null)

            this.status = CameraStatus.CAPTURE_STARTING
            camera!!.createCaptureSession(
                    listOf(imageReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.i(TAG, "capture session onConfigured, status=${status}")
                            if (camera != null) {
                                captureSession = session
                                updateStatus(CameraStatus.CAPTURE_READY)
                            }
                            else {
                                Log.i(TAG, "onConfigured: camera is null, not starting")
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            updateStatus(CameraStatus.ERROR)
                        }

                        override fun onClosed(session: CameraCaptureSession) {
                            Log.i(TAG, "capture session onClosed, status=${status}")
                            super.onClosed(session)
                            if (status != CameraStatus.CLOSING && status != CameraStatus.CLOSED &&
                                    camera != null) {
                                updateStatus(CameraStatus.OPENED)
                            }
                        }
                    },
                    null)
        }
        catch (ex: CameraAccessException) {
            Log.e(TAG, "setupCameraPreview error", ex)
        }
    }

    private fun startCapture() {
        val request = when(this.targetStatus) {
            CameraStatus.CAPTURING_PREVIEW ->
                camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            CameraStatus.CAPTURING_PHOTO ->
                camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            CameraStatus.CAPTURING_VIDEO ->
                camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            else -> {
                throw IllegalStateException("Invalid status: " + this.status)
            }
        }
        request.addTarget(imageReader!!.surface)
        if (this.zoomRatio > 0) {
            val cc = cameraCharacteristics
            val baseRect = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val maxZoom = cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)!!
            val zoom = Math.pow(maxZoom.toDouble(), this.zoomRatio)
            val width = (baseRect.width() / zoom).toInt()
            val height = (baseRect.height() / zoom).toInt()
            val cx = baseRect.centerX()
            val cy = baseRect.centerY()
            val zoomedRect = Rect(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)
            request.set(CaptureRequest.SCALER_CROP_REGION, zoomedRect)
        }
        if (this.targetStatus == CameraStatus.CAPTURING_PHOTO) {
            captureSession!!.capture(request.build(), null, null)
        }
        else {
            captureSession!!.setRepeatingRequest(request.build(), null, null)
        }
        this.status = this.targetStatus
        Log.i(TAG, "Capture started, status=${status}")
    }

    fun setZoom(zoomRatio: Double) {
        if (zoomRatio < 0 || zoomRatio > 1) {
            throw IllegalArgumentException("Invalid zoom ratio: ${zoomRatio}")
        }
        this.zoomRatio = zoomRatio
        if (this.status.isCapturing()) {
            startCapture()
        }
    }

    fun zoomIn(zoomAmount: Double) {
        val newZoom = this.zoomRatio + zoomAmount
        setZoom(minOf(1.0, maxOf(0.0, newZoom)))
    }

    private fun stopCaptureSession() {
        Log.i(TAG, "stopCameraSession")
        captureSession?.close()
        captureSession = null
    }

    private fun stopCamera() {
        Log.i(TAG, "stopCamera")
        stopCaptureSession()
        imageReader?.close()
        imageReader = null
        camera?.close()
        camera = null
    }

    companion object {
        const val TAG = "CameraImageGenerator"

        fun pickBestSize(sizes: Array<Size>, target: Size): Size {
            fun differenceFromRequested(s: Size) =
                    Math.abs(s.width - target.width) + Math.abs(s.height - target.height)
            var bestSize = sizes[0]
            var bestDiff = differenceFromRequested(sizes[0])
            for (i in 1 until sizes.size) {
                val diff = differenceFromRequested(sizes[i])
                if (diff < bestDiff) {
                    bestSize = sizes[i]
                    bestDiff = diff
                }
            }
            return bestSize
        }
    }
}