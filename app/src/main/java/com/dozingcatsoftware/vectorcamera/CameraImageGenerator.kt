package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.os.Handler
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type
import android.util.Log
import android.util.Size

class CameraImageGenerator(val context: Context, val rs: RenderScript,
                           val cameraManager: CameraManager, val cameraId: String,
                           val timestampFn: () -> Long = System::currentTimeMillis) {


    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureSize: Size? = null
    var status = CameraStatus.CLOSED
    private var targetStatus = CameraStatus.CLOSED
    private var imageAllocationCallback: ((CameraImage) -> Unit)? = null
    private var handler = Handler()

    private val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

    private var allocation: Allocation? = null

    // https://stackoverflow.com/questions/33902832/upside-down-camera-preview-byte-array
    // https://www.reddit.com/r/Android/comments/3rjbo8/nexus5x_marshmallow_camera_problem/cwqzqgh
    private val imageOrientation = {
        val facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
        val isFrontFacing = (facing == CameraMetadata.LENS_FACING_FRONT)
        val orientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        if (isFrontFacing && orientation == 90 || !isFrontFacing && orientation == 270) {
            ImageOrientation.ROTATED_180
        }
        else {
            ImageOrientation.NORMAL
        }
    }()

    fun start(targetStatus: CameraStatus, targetSize: Size,
              imageAllocationCallback: ((CameraImage) -> Unit)? = null) {
        this.captureSize = pickBestSize(
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(ImageFormat.YUV_420_888),
                targetSize)
        this.targetStatus = targetStatus
        this.imageAllocationCallback = imageAllocationCallback

        if (this.status.isCapturing()) {
            Log.i(TAG, "Restarting capture")
            captureSession!!.abortCaptures()
            captureSession!!.close()
        }
        else {
            updateStatus(this.status)
        }
    }

    fun stop() {
        this.targetStatus = CameraStatus.CLOSED
        updateStatus(this.status)
    }

    private fun updateStatus(status: CameraStatus) {
        Log.i(TAG, "CameraStatus change: " + status)
        this.status = status

        if (this.targetStatus == CameraStatus.CLOSED) {
            if (this.status.isCapturing()) {
                this.status = CameraStatus.CLOSING
                stopCaptureSession()
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

    fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        stopCamera()
        try {
            updateStatus(CameraStatus.OPENING)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    updateStatus(CameraStatus.OPENED)
                }

                override fun onDisconnected(cam: CameraDevice) {
                    updateStatus(CameraStatus.CLOSED)
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
            Log.i(TAG, "Using camera size: " + size)

            allocation = createRenderscriptAllocation(size)
            allocation!!.setOnBufferAvailableListener({
                // Log.i(TAG, "Got RS buffer")
                if (captureSession != null) {
                    if (this.imageAllocationCallback != null) {
                        this.imageAllocationCallback!!(CameraImage.withAllocation(
                                rs, allocation!!, imageOrientation, status, timestampFn()))
                    }
                }
                else {
                    Log.i(TAG, "captureSession is null, closing image")
                }
            })

            camera!!.createCaptureSession(
                    listOf(allocation!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            updateStatus(CameraStatus.CAPTURE_READY)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            updateStatus(CameraStatus.ERROR)
                        }

                        override fun onClosed(session: CameraCaptureSession?) {
                            super.onClosed(session)
                            updateStatus(CameraStatus.OPENED)
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
        request.addTarget(allocation!!.surface)
        if (this.targetStatus == CameraStatus.CAPTURING_PHOTO) {
            captureSession!!.capture(request.build(), null, null)
        }
        else {
            captureSession!!.setRepeatingRequest(request.build(), null, null)
        }
        this.status = this.targetStatus
    }

    private fun stopCaptureSession() {
        Log.i(TAG, "stopCameraSession")
        captureSession?.close()
        captureSession = null
    }

    private fun stopCamera() {
        Log.i(TAG, "stopCamera")
        stopCaptureSession()
        camera?.close()
        camera = null
    }

    private fun createRenderscriptAllocation(size: Size): Allocation {
        val yuvTypeBuilder = Type.Builder(rs, Element.YUV(rs))
        yuvTypeBuilder.setX(size.width)
        yuvTypeBuilder.setY(size.height)
        yuvTypeBuilder.setYuvFormat(ImageFormat.YUV_420_888)
        // yuvTypeBuilder.setYuvFormat(ImageFormat.NV21)
        return Allocation.createTyped(rs, yuvTypeBuilder.create(),
                Allocation.USAGE_IO_INPUT or Allocation.USAGE_SCRIPT)
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