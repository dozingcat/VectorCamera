package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.renderscript.RenderScript
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.dozingcatsoftware.boojiecam.effect.CombinationEffect
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : Activity() {

    private val handler = Handler()
    private lateinit var cameraSelector: CameraSelector
    private lateinit var cameraImageGenerator: CameraImageGenerator

    private var imageProcessor = CameraAllocationProcessor()
    private var preferredImageSize = ImageSize.HALF_SCREEN

    private val photoLibrary = PhotoLibrary.defaultLibrary()

    private lateinit var rs: RenderScript
    private val allEffectFactories = EffectRegistry.defaultEffectFactories()
    private var effectIndex = 0
    private var inEffectSelectionMode = false
    private var lastBitmapTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)

        // Use PROFILE type only on first run?
        rs = RenderScript.create(this, RenderScript.ContextType.NORMAL)

        cameraSelector = CameraSelector(this)
        cameraImageGenerator = cameraSelector.createImageGenerator(rs)

        switchCameraButton.setOnClickListener(this::switchToNextCamera)
        switchResolutionButton.setOnClickListener(this::switchResolution)
        switchEffectButton.setOnClickListener(this::switchEffect)
        takePictureButton.setOnClickListener(this::takePicture)
        libraryButton.setOnClickListener(this::gotoLibrary)
        overlayView.touchEventHandler = this::handleOverlayViewTouchEvent
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        checkPermissionAndStartCamera()
        overlayView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE
    }

    override fun onPause() {
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
                            this::handleAllocationFromCamera)
                }
            }
        }
    }

    private fun checkPermissionAndStartCamera() {
        if (PermissionsChecker.hasCameraPermission(this)) {
            restartCameraImageGenerator()
        }
        else {
            PermissionsChecker.requestCameraAndStoragePermissions(this)
        }
    }

    private fun handleGeneratedBitmap(processedBitmap: ProcessedBitmap) {
        handler.post(fun() {
            if (lastBitmapTimestamp > processedBitmap.sourceImage.timestamp) {
                return
            }
            lastBitmapTimestamp = processedBitmap.sourceImage.timestamp
            overlayView.processedBitmap = processedBitmap
            overlayView.invalidate()
            if (processedBitmap.sourceImage.status == CameraStatus.CAPTURING_PHOTO) {
                Log.i(TAG, "Saving picture")
                Thread({
                    val yuvBytes = flattenedYuvImageBytes(
                            rs, processedBitmap.sourceImage.singleYuvAllocation!!)
                    photoLibrary.savePhoto(processedBitmap, yuvBytes,
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

    private fun handleAllocationFromCamera(camAllocation: CameraImage) {
        handler.post(fun() {
            if (camAllocation.status == CameraStatus.CAPTURING_PHOTO) {
                Log.i(TAG, "Restarting preview capture")
                restartCameraImageGenerator()
            }
            this.imageProcessor.queueAllocation(camAllocation)
        })
    }

    private fun restartCameraImageGenerator() {
        // cameraImageGenerator?.pause()
        Log.i(TAG, "recreateCameraImageGenerator: " + this.targetCameraImageSize())
        cameraImageGenerator.start(
                CameraStatus.CAPTURING_PREVIEW,
                this.targetCameraImageSize(),
                this::handleAllocationFromCamera)
        this.imageProcessor.start(allEffectFactories[effectIndex](rs), this::handleGeneratedBitmap)
    }

    private fun switchToNextCamera(view: View) {
        if (cameraImageGenerator.status != CameraStatus.CAPTURING_PREVIEW) {
            return
        }
        imageProcessor.pause()
        cameraSelector.selectNextCamera()
        cameraImageGenerator.stop()
        cameraImageGenerator = cameraSelector.createImageGenerator(rs)
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
        inEffectSelectionMode = !inEffectSelectionMode
        if (inEffectSelectionMode) {
            val comboEffect = CombinationEffect(rs, allEffectFactories)
            // TODO: Reduce preview size so tiles don't compute full resolution?
            this.imageProcessor.start(comboEffect, this::handleGeneratedBitmap)
        }
        else {
            imageProcessor.start(allEffectFactories[effectIndex](rs), this::handleGeneratedBitmap)
        }
    }

    private fun handleOverlayViewTouchEvent(view: OverlayView, event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (inEffectSelectionMode) {
                val gridSize = Math.floor(Math.sqrt(allEffectFactories.size.toDouble())).toInt()
                val tileWidth = view.width / gridSize
                val tileHeight = view.height / gridSize
                val tileX = (event.x / tileWidth).toInt()
                val tileY = (event.y / tileHeight).toInt()
                val index = gridSize * tileY + tileX

                effectIndex = Math.min(Math.max(0, index), allEffectFactories.size - 1)
                restartCameraImageGenerator()

                imageProcessor.start(allEffectFactories[effectIndex](rs), this::handleGeneratedBitmap)
                inEffectSelectionMode = false
            }
        }
    }

    private fun takePicture(view: View) {
        if (cameraImageGenerator.status != CameraStatus.CAPTURING_PREVIEW) {
            return
        }
        imageProcessor.pause()
        cameraImageGenerator.start(
                CameraStatus.CAPTURING_PHOTO,
                this.cameraImageSizeForSavedPicture(),
                this::handleAllocationFromCamera)
    }

    private fun gotoLibrary(view: View) {
        this.startActivity(Intent(this, ImageListActivity::class.java))
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
