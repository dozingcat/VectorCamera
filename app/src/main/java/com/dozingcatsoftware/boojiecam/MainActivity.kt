package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.View
import android.view.Window
import android.view.WindowManager
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File

class MainActivity : Activity() {

    private val handler = Handler()
    private val lifeGenerator = LifeBitmapGenerator()
    private lateinit var cameraSelector: CameraSelector
    private lateinit var cameraImageGenerator: CameraImageGenerator

    private lateinit var imageProcessor: CameraImageProcessor
    private var preferredImageSize = ImageSize.HALF_SCREEN

    private val photoLibrary = PhotoLibrary(
            File(Environment.getExternalStorageDirectory(), "BoojieCam"))

    private val allImageProcessors = arrayOf(
            EdgeColorImageProcessor(),
            EdgeImageProcessor.withFixedColors(0x000000, 0x00ff00),
            EdgeImageProcessor.withFixedColors(0x00ffff, 0xff0000),
            EdgeImageProcessor.withFixedColors(0xffffff, 0x000000),
            EdgeImageProcessor.withLinearGradient(0x000000, 0xff0000, 0x0000ff),
            EdgeImageProcessor.withRadialGradient(0x191970, 0xffff00, 0xff4500),
            GrayscaleImageGenerator()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)

        cameraSelector = CameraSelector(this)
        cameraImageGenerator = cameraSelector.createImageGenerator()
        imageProcessor = allImageProcessors[0]

        switchCameraButton.setOnClickListener(this::switchToNextCamera)
        switchResolutionButton.setOnClickListener(this::switchResolution)
        switchEffectButton.setOnClickListener(this::switchEffect)
        takePictureButton.setOnClickListener(this::takePicture)
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        // lifeGenerator.start(this::handleGeneratedBitmap)
        checkPermissionAndStartCamera()
    }

    override fun onPause() {
        lifeGenerator.pause()
        imageProcessor.pause()
        cameraImageGenerator.stop()
        super.onPause()
    }

    private fun targetCameraImageSize(): Size {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(metrics)
        val displayWidth = metrics.widthPixels
        val displayHeight = metrics.heightPixels
        return when (preferredImageSize) {
            ImageSize.FULL_SCREEN -> Size(displayWidth, displayHeight)
            ImageSize.HALF_SCREEN -> Size(displayWidth / 2, displayHeight / 2)
            ImageSize.VIDEO_RECORDING -> Size(640, 360)
        }
    }

    private fun cameraImageSizeForSavedPicture() = Size(1920, 1080)

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            PermissionsChecker.CAMERA_AND_STORAGE_REQUEST_CODE -> {
                if (PermissionsChecker.hasCameraPermission(this)) {
                    cameraImageGenerator.start(
                            CameraStatus.CAPTURING_PREVIEW,
                            this.targetCameraImageSize(),
                            this::handleImageFromCamera)
                }
            }
        }
    }

    private fun checkPermissionAndStartCamera() {
        if (PermissionsChecker.hasCameraPermission(this)) {
            cameraImageGenerator.start(
                    CameraStatus.CAPTURING_PREVIEW,
                    this.targetCameraImageSize(),
                    this::handleImageFromCamera)
        }
        else {
            PermissionsChecker.requestCameraAndStoragePermissions(this)
        }
    }

    private fun handleGeneratedBitmap(processedBitmap: ProcessedBitmap) {
        handler.post({
            overlayView.processedBitmap = processedBitmap
            overlayView.invalidate()
            if (processedBitmap.sourceImage.status == CameraStatus.CAPTURING_PHOTO) {
                Log.i(TAG, "Saving picture")
                Thread({
                    photoLibrary.savePhoto(processedBitmap,
                            fun(photoId: String) {
                                Log.i(TAG, "Saved $photoId")
                            },
                            fun(ex: Exception) {
                                Log.w(TAG, "Error saving photo: " + ex)
                            })
                }).start()
            }
        })
    }

    private fun handleImageFromCamera(image: CameraImage) {
        handler.post({
            imageProcessor.start(this::handleGeneratedBitmap)
            // Log.i(TAG, "Received image from camera")
            if (image.status == CameraStatus.CAPTURING_PHOTO) {
                Log.i(TAG, "Restarting preview capture")
                cameraImageGenerator.start(
                        CameraStatus.CAPTURING_PREVIEW,
                        this.targetCameraImageSize(),
                        this::handleImageFromCamera)
                // We're going to save the raw data from the camera image, so extract it now.
                // (If we wait until handleGeneratedBitmap is called, the underlying
                // android.media.Image will have been closed).
                val yuvBytes = flattenedYuvImageBytes(image.image)
                val inMemoryImage = CameraImage(
                        PlanarImage.fromFlattenedYuvBytes(yuvBytes,
                                image.image.width, image.image.height),
                        image.orientation, image.status, image.timestamp)
                image.close()
                imageProcessor.queueImage(inMemoryImage)
            }
            else {
                imageProcessor.queueImage(image)
            }
        })
    }

    private fun restartCameraImageGenerator() {
        // cameraImageGenerator?.pause()
        Log.i(TAG, "recreateCameraImageGenerator: " + this.targetCameraImageSize())
        cameraImageGenerator.start(
                CameraStatus.CAPTURING_PREVIEW,
                this.targetCameraImageSize(),
                this::handleImageFromCamera)
    }

    private fun switchToNextCamera(view: View) {
        if (cameraImageGenerator.status != CameraStatus.CAPTURING_PREVIEW) {
            return
        }
        imageProcessor.pause()
        cameraSelector.selectNextCamera()
        cameraImageGenerator.stop()
        cameraImageGenerator = cameraSelector.createImageGenerator()
        restartCameraImageGenerator()
    }

    private fun switchResolution(view: View) {
        if (cameraImageGenerator.status != CameraStatus.CAPTURING_PREVIEW) {
            return
        }
        preferredImageSize =
                if (preferredImageSize == ImageSize.FULL_SCREEN)
                    ImageSize.HALF_SCREEN
                else
                    ImageSize.FULL_SCREEN
        restartCameraImageGenerator()
    }

    private fun switchEffect(view: View) {
        imageProcessor.pause()
        val index = allImageProcessors.indexOf(imageProcessor)
        imageProcessor = allImageProcessors[(index + 1) % allImageProcessors.size]
    }

    private fun takePicture(view: View) {
        if (cameraImageGenerator.status != CameraStatus.CAPTURING_PREVIEW) {
            return
        }
        imageProcessor.pause()
        cameraImageGenerator.start(
                CameraStatus.CAPTURING_PHOTO,
                this.cameraImageSizeForSavedPicture(),
                this::handleImageFromCamera)
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        val TAG = "MainActivity"

        // Used to load the 'native-lib' library on application startup.
        init {
            System.loadLibrary("native-lib")
        }
    }
}
