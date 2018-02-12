package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.renderscript.RenderScript
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.Window
import com.dozingcatsoftware.boojiecam.effect.CombinationEffect
import com.dozingcatsoftware.boojiecam.effect.Effect
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry
import com.dozingcatsoftware.util.getDisplaySize
import kotlinx.android.synthetic.main.activity_main.*
import java.io.FileOutputStream

enum class ShutterMode {IMAGE, VIDEO}

class MainActivity : Activity() {

    private val handler = Handler()
    private lateinit var cameraSelector: CameraSelector
    private lateinit var cameraImageGenerator: CameraImageGenerator
    private val preferences = BCPreferences(this)

    private lateinit var imageProcessor: CameraAllocationProcessor
    private var preferredImageSize = ImageSize.HALF_SCREEN

    private val photoLibrary = PhotoLibrary.defaultLibrary()

    private lateinit var rs: RenderScript
    private lateinit var displaySize: Size
    private val allEffectFactories = EffectRegistry.defaultEffectFactories()
    private var currentEffect: Effect? = null
    private var previousEffect: Effect? = null
    private var inEffectSelectionMode = false
    private var lastBitmapTimestamp = 0L

    private var videoRecorder: VideoRecorder? = null
    private var videoFrameMetadata: MediaMetadata? = null
    private var audioRecorder: AudioRecorder? = null
    private var audioStartTimestamp = 0L
    private lateinit var previousImageSize: ImageSize
    private var shutterMode = ShutterMode.IMAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)
        PreferenceManager.setDefaultValues(this.baseContext, R.xml.preferences, false)

        displaySize = getDisplaySize(this)
        // Use PROFILE type only on first run?
        rs = RenderScript.create(this, RenderScript.ContextType.NORMAL)
        imageProcessor = CameraAllocationProcessor(rs)

        currentEffect = effectFromPreferences()

        cameraSelector = CameraSelector(this)
        cameraImageGenerator = cameraSelector.createImageGenerator(rs)

        moreOptionsButton.setOnClickListener({_ ->
            moreOptionsLayout.visibility =
                    if (moreOptionsLayout.visibility == View.VISIBLE) View.INVISIBLE
                    else View.VISIBLE
        })

        toggleVideoButton.setOnClickListener(this::toggleVideoMode)
        switchCameraButton.setOnClickListener(this::switchToNextCamera)
        switchResolutionButton.setOnClickListener(this::switchResolution)
        switchEffectButton.setOnClickListener(this::toggleEffectSelectionMode)
        libraryButton.setOnClickListener(this::gotoLibrary)
        helpButton.setOnClickListener(this::gotoHelp)
        settingsButton.setOnClickListener(this::gotoPreferences)
        convertPictureButton.setOnClickListener(this::convertExistingPicture)
        overlayView.touchEventHandler = this::handleOverlayViewTouchEvent
        cameraActionButton.onShutterButtonClick = this::handleShutterClick
        cameraActionButton.onShutterButtonFocus = this::handleShutterFocus

        // Preload the effect classes so there's not a delay when switching to the effect grid.
        Thread({
            Log.i(TAG, "Starting effect loading thread")
            for (ef in allEffectFactories) {
                ef(rs, preferences.lookupFunction)
            }
            Log.i(TAG, "Done loading effects")
        }).start()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        when (requestCode) {
            ACTIVITY_CHOOSE_PICTURE -> {
                if (resultCode == RESULT_OK) {
                    Log.i(TAG, "Selected photo: ${intent!!.data}")
                    Thread({
                        try {
                            val imageId = ProcessImageOperation().processImage(this, intent!!.data)
                            handler.post({
                                ViewImageActivity.startActivityWithImageId(this, imageId)
                            })
                        }
                        catch (ex: Exception) {
                            Log.w(TAG, "Error processing image", ex)
                        }
                    }).start()
                }
            }
        }
    }

    fun updateControls() {
        var shutterResId = R.drawable.btn_camera_shutter_holo
        if (shutterMode == ShutterMode.VIDEO) {
            shutterResId = if (videoRecorder == null) R.drawable.btn_video_shutter_holo
                           else R.drawable.btn_video_shutter_recording_holo
        }
        cameraActionButton.setImageResource(shutterResId)

        toggleVideoButton.setImageResource(when(shutterMode) {
            ShutterMode.IMAGE -> R.drawable.ic_photo_camera_white_36dp
            ShutterMode.VIDEO -> R.drawable.ic_videocam_white_36dp
        })

        switchCameraButton.setImageResource(
                if (cameraSelector.isSelectedCameraFrontFacing())
                    R.drawable.ic_camera_front_white_36dp
                else R.drawable.ic_camera_rear_white_36dp)
    }

    private fun targetCameraImageSize(): Size {
        return when (preferredImageSize) {
            ImageSize.FULL_SCREEN -> displaySize
            ImageSize.HALF_SCREEN -> Size(displaySize.width / 2, displaySize.height / 2)
            ImageSize.VIDEO_RECORDING -> Size(640, 360)
            ImageSize.EFFECT_GRID -> Size(displaySize.width / 4, displaySize.height / 4)
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

    private fun handleGeneratedBitmap(pb: ProcessedBitmap) {
        handler.post(fun() {
            if (lastBitmapTimestamp > pb.sourceImage.timestamp) {
                return
            }
            lastBitmapTimestamp = pb.sourceImage.timestamp
            overlayView.processedBitmap = pb
            overlayView.invalidate()
            // Save image or video frame if necessary.
            if (pb.sourceImage.status == CameraStatus.CAPTURING_PHOTO) {
                Log.i(TAG, "Saving picture")
                if (pb.yuvBytes == null) {
                    Log.w(TAG, "yuvBytes not set for saved image")
                }
                Thread({
                    try {
                        val photoId = photoLibrary.savePhoto(this, pb)
                        handler.post({
                            ViewImageActivity.startActivityWithImageId(this, photoId)
                        })
                        // Write the PNG in the background since it's slower.
                        photoLibrary.writePngImage(this, pb, photoId)
                    }
                    catch (ex: Exception) {
                        Log.w(TAG, "Error saving photo: " + ex)
                    }
                }).start()
            }
            if (pb.sourceImage.status == CameraStatus.CAPTURING_VIDEO) {
                val vr = videoRecorder
                if (vr != null) {
                    if (pb.yuvBytes != null) {
                        if (videoFrameMetadata == null) {
                            videoFrameMetadata = MediaMetadata(
                                    MediaType.VIDEO,
                                    currentEffect!!.effectMetadata(),
                                    pb.sourceImage.width(),
                                    pb.sourceImage.height(),
                                    pb.sourceImage.orientation,
                                    pb.sourceImage.timestamp)
                        }
                        vr.recordFrame(pb.sourceImage.timestamp, pb.yuvBytes)
                    }
                    else {
                        Log.w(TAG, "yuvBytes not set for video frame")
                    }
                }
            }
        })
    }

    private fun effectFromPreferences(): Effect {
        return preferences.effect(rs, {allEffectFactories[0](rs, preferences.lookupFunction)})
    }

    private fun handleAllocationFromCamera(imageFromCamera: CameraImage) {
        handler.post(fun() {
            // Slightly ugly but some effects need the display size.
            val cameraImage = imageFromCamera.withDisplaySize(displaySize)
            if (cameraImage.status == CameraStatus.CAPTURING_PHOTO) {
                Log.i(TAG, "Restarting preview capture")
                restartCameraImageGenerator()
            }
            this.imageProcessor.queueAllocation(cameraImage)
        })
    }

    private fun restartCameraImageGenerator(
            cameraStatus: CameraStatus = CameraStatus.CAPTURING_PREVIEW) {
        Log.i(TAG, "recreateCameraImageGenerator: " + this.targetCameraImageSize())
        if (!inEffectSelectionMode) {
            currentEffect = effectFromPreferences()
        }
        cameraImageGenerator.start(
                cameraStatus,
                this.targetCameraImageSize(),
                this::handleAllocationFromCamera)
        this.imageProcessor.start(currentEffect!!, this::handleGeneratedBitmap)
    }

    private fun toggleVideoMode(view: View) {
        shutterMode = if (shutterMode == ShutterMode.VIDEO) ShutterMode.IMAGE else ShutterMode.VIDEO
        updateControls()
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
        updateControls()
    }

    private fun switchResolution(view: View) {
        if (cameraImageGenerator.status != CameraStatus.CAPTURING_PREVIEW) {
            return
        }
        if (inEffectSelectionMode) {
            return
        }
        preferredImageSize =
                if (preferredImageSize == ImageSize.FULL_SCREEN)
                    ImageSize.HALF_SCREEN
                else
                    ImageSize.FULL_SCREEN
        restartCameraImageGenerator()
    }

    private fun toggleEffectSelectionMode(view: View?) {
        inEffectSelectionMode = !inEffectSelectionMode
        if (inEffectSelectionMode) {
            previousEffect = currentEffect
            val t1 = System.currentTimeMillis()
            currentEffect = CombinationEffect(rs, preferences.lookupFunction, allEffectFactories)
            val t2 = System.currentTimeMillis()
            previousImageSize = preferredImageSize
            preferredImageSize = ImageSize.EFFECT_GRID
            controlLayout.visibility = View.GONE
            Log.i(TAG, "CombinationEffect time: ${t2-t1}")
        }
        else {
            currentEffect = previousEffect
            preferredImageSize = previousImageSize
        }
        restartCameraImageGenerator()
        imageProcessor.start(currentEffect!!, this::handleGeneratedBitmap)
    }

    private fun handleOverlayViewTouchEvent(view: OverlayView, event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (inEffectSelectionMode) {
                val gridSize = Math.ceil(Math.sqrt(allEffectFactories.size.toDouble())).toInt()
                val tileWidth = view.width / gridSize
                val tileHeight = view.height / gridSize
                val tileX = (event.x / tileWidth).toInt()
                val tileY = (event.y / tileHeight).toInt()
                val index = gridSize * tileY + tileX

                val effectIndex = Math.min(Math.max(0, index), allEffectFactories.size - 1)
                val eff = allEffectFactories[effectIndex](rs, preferences.lookupFunction)
                currentEffect = eff
                preferences.saveEffectInfo(eff.effectName(), eff.effectParameters())
                preferredImageSize = previousImageSize
                restartCameraImageGenerator()
                imageProcessor.start(currentEffect!!, this::handleGeneratedBitmap)
                inEffectSelectionMode = false
            }
            else {
                controlLayout.visibility =
                        if (controlLayout.visibility == View.VISIBLE) View.GONE
                        else View.VISIBLE
            }
        }
    }

    override fun onBackPressed() {
        if (inEffectSelectionMode) {
            toggleEffectSelectionMode(null)
        }
        else {
            super.onBackPressed()
        }
    }

    private fun handleShutterClick() {
        when (shutterMode) {
            ShutterMode.IMAGE -> takePicture()
            ShutterMode.VIDEO -> toggleVideoRecording()
        }
    }

    private fun handleShutterFocus(pressed: Boolean) {
        if (pressed) {
            var resId = R.drawable.btn_camera_shutter_pressed_holo
            if (shutterMode == ShutterMode.VIDEO) {
                resId = if (videoRecorder != null)
                            R.drawable.btn_video_shutter_recording_pressed_holo
                        else R.drawable.btn_video_shutter_pressed_holo
            }
            cameraActionButton.setImageResource(resId)
        }
        else {
            var resId = R.drawable.btn_camera_shutter_holo
            if (shutterMode == ShutterMode.VIDEO) {
                resId = if (videoRecorder != null)
                    R.drawable.btn_video_shutter_recording_holo
                else R.drawable.btn_video_shutter_holo
            }
            cameraActionButton.setImageResource(resId)
        }
    }

    private fun takePicture() {
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
        ImageListActivity.startIntent(this)
    }

    private fun gotoPreferences(view: View) {
        BCPreferencesActivity.startIntent(this)
    }

    private fun gotoHelp(view: View) {
        AboutActivity.startIntent(this)
    }

    private fun convertExistingPicture(view: View) {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI)
        this.startActivityForResult(intent, ACTIVITY_CHOOSE_PICTURE)
    }

    private fun toggleVideoRecording() {
        if (videoRecorder == null) {
            Log.i(TAG, "Starting video recording")
            val videoId = photoLibrary.itemIdForTimestamp(System.currentTimeMillis())
            val videoStream = photoLibrary.createTempRawVideoFileOutputStreamForItemId(videoId)
            previousImageSize = preferredImageSize
            preferredImageSize = ImageSize.VIDEO_RECORDING
            // This might be cleaner with a MediaRecorder class that encapsulates audio and video.
            videoFrameMetadata = null
            restartCameraImageGenerator(CameraStatus.CAPTURING_VIDEO)
            videoRecorder = VideoRecorder(videoId, videoStream, this::videoRecorderUpdated)
            videoRecorder!!.start()

            val audioStream = photoLibrary.createTempRawAudioFileOutputStreamForItemId(videoId)
            audioRecorder = AudioRecorder(videoId, audioStream as FileOutputStream)
            audioRecorder!!.start()
        }
        else {
            Log.i(TAG, "Stopping video recording")
            audioStartTimestamp = audioRecorder!!.recordingStartTimestamp
            try {
                videoRecorder!!.stop()
            }
            finally {
                videoRecorder = null
                try {
                    audioRecorder!!.stop()
                }
                finally {
                    audioRecorder = null
                }
            }
        }
        updateControls()
    }

    private fun videoRecorderUpdated(recorder: VideoRecorder, status: VideoRecorder.Status) {
        // This is called from the video recording thread, so post to the main thread.
        handler.post {
            when (status) {
                VideoRecorder.Status.RUNNING -> {
                    // TODO: Update recording stats for display.
                    Log.i(TAG, "Wrote video frame, frames: " + recorder.frameTimestamps.size)
                }
                VideoRecorder.Status.FINISHED -> {
                    Log.i(TAG, "Video recording stopped, writing to library")
                    preferredImageSize = previousImageSize
                    restartCameraImageGenerator()
                    // TODO: Get audio start timestamp and persist in metadata.
                    photoLibrary.saveVideo(
                            this,
                            recorder.videoId,
                            videoFrameMetadata!!,
                            recorder.frameTimestamps,
                            audioStartTimestamp)
                    ViewVideoActivity.startActivityWithVideoId(this, recorder.videoId)
                }
            }
        }
    }

    companion object {
        const val TAG = "MainActivity"

        const val ACTIVITY_CHOOSE_PICTURE = 1
    }
}
