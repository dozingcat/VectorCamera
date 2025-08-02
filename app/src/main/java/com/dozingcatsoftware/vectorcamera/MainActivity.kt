package com.dozingcatsoftware.vectorcamera

import RunningStats
import android.app.ProgressDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore

import android.util.Log
import android.util.Size
import android.util.TypedValue
import androidx.preference.PreferenceManager
import com.dozingcatsoftware.util.getLandscapeDisplaySize
import java.io.FileOutputStream
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.dozingcatsoftware.util.adjustPaddingForSystemUi
import com.dozingcatsoftware.vectorcamera.databinding.ActivityMainBinding
import com.dozingcatsoftware.vectorcamera.effect.*


enum class ShutterMode {IMAGE, VIDEO}

class MainActivity : AppCompatActivity() {

    private val handler = Handler()
    private lateinit var cameraSelector: CameraSelector
    private lateinit var cameraImageGenerator: CameraImageGenerator
    private val preferences = VCPreferences(this)

    private lateinit var imageProcessor: CameraImageProcessor
    private var preferredImageSize = ImageSize.HALF_SCREEN

    private lateinit var photoLibrary: PhotoLibrary


    private val effectRegistry = EffectRegistry()
    private var currentEffect: Effect? = null
    private var previousEffect: Effect? = null
    private var inEffectSelectionMode = false
    private var lastBitmapTimestamp = 0L
    private var previousFingerSpacing = 0.0

    // For updating custom schemes, we need to keep the effect index and scheme ID.
    private var effectIndex = 0
    private var customSchemeId = ""

    private var videoRecorder: VideoRecorder? = null
    private var videoFrameMetadata: MediaMetadata? = null
    private var audioRecorder: AudioRecorder? = null
    private var audioStartTimestamp = 0L
    private var shutterMode = ShutterMode.IMAGE

    private var layoutIsPortrait = false

    private var askedForPermissions = false
    private var libraryMigrationDone = false

    private var showDebugInfo = false

    private lateinit var binding: ActivityMainBinding
    private lateinit var onBackPressedCallback: OnBackPressedCallback

    val renderTimeStats = RunningStats()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adjustPaddingForSystemUi(binding.layoutWithPadding)

        photoLibrary = PhotoLibrary.defaultLibrary(this)

        PreferenceManager.setDefaultValues(this.baseContext, R.xml.preferences, false)

        imageProcessor = CameraImageProcessor()

        currentEffect = effectFromPreferences()
        preferredImageSize =
                if (preferences.useHighQualityPreview()) ImageSize.FULL_SCREEN
                else ImageSize.HALF_SCREEN

        cameraSelector = CameraSelector(this)
        cameraImageGenerator = cameraSelector.createImageGenerator()

        binding.toggleVideoButton.setOnClickListener(this::toggleVideoMode)
        binding.switchCameraButton.setOnClickListener(this::switchToNextCamera)
        binding.switchResolutionButton.setOnClickListener(this::switchResolution)
        binding.switchEffectButton.setOnClickListener(this::toggleEffectSelectionMode)
        binding.libraryButton.setOnClickListener(this::gotoLibrary)
        binding.helpButton.setOnClickListener(this::gotoHelp)
        binding.settingsButton.setOnClickListener(this::gotoPreferences)
        binding.convertPictureButton.setOnClickListener(this::convertExistingPicture)
        binding.overlayView.touchEventHandler = this::handleOverlayViewTouchEvent
        binding.cameraActionButton.onShutterButtonClick = this::handleShutterClick
        binding.cameraActionButton.onShutterButtonFocus = this::handleShutterFocus
        binding.editSchemeView.activity = this
        binding.editSchemeView.changeCallback = this::handleCustomColorSchemeChanged

        // If the effect selection grid is visible, a back navigation should hide
        // the grid without changing the effect, and should remain in this activity.
        onBackPressedCallback = object: OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (inEffectSelectionMode) {
                    toggleEffectSelectionMode(null)
                }
            }
        }
        onBackPressedDispatcher.addCallback(onBackPressedCallback)

        // Preload the effect classes so there's not a delay when switching to the effect grid.
        Thread({
            Log.i(TAG, "Starting effect loading thread")
            for (i in 0 until effectRegistry.defaultEffectCount()) {
                effectRegistry.defaultEffectAtIndex(
                    i, preferences.lookupFunction, EffectContext.PRELOAD)
            }
            Log.i(TAG, "Done loading effects")
        }).start()
        updateControls()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndStartCamera()
        binding.overlayView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE

        // View size is zero in onResume, have to wait for layout notification.
        var listener: ViewTreeObserver.OnGlobalLayoutListener? = null
        listener = ViewTreeObserver.OnGlobalLayoutListener {
            updateLayout(isPortraitOrientation())
            binding.overlayView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        binding.overlayView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        showDebugInfo = preferences.showDebugInfo()
    }

    override fun onPause() {
        imageProcessor.pause()
        cameraImageGenerator.stop()
        if (videoRecorder != null) {
            toggleVideoRecording()
            // HACK: Don't clear the temp directory in this case because it holds the recorded data.
        }
        else {
            Log.i(TAG, "Clearing temp dir")
            photoLibrary.clearTempDirectories()
        }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        Log.i(TAG, "configurationChanged: ${newConfig.orientation}")
        super.onConfigurationChanged(newConfig)
        val isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isPortrait != layoutIsPortrait) {
            updateLayout(isPortrait)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        when (requestCode) {
            ACTIVITY_CHOOSE_PICTURE -> {
                if (resultCode == RESULT_OK) {
                    Log.i(TAG, "Selected photo: ${intent!!.data}")
                    Thread({
                        try {
                            val imageId = ProcessImageOperation().processImage(this, intent.data!!)
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

    private fun isCameraKey(keyCode: Int): Boolean {
        if (inEffectSelectionMode) return false
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        return keyCode == KeyEvent.KEYCODE_CAMERA || (isVolumeKey && preferences.takePictureWithVolumeButton())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isCameraKey(keyCode)) {
            binding.controlLayout.visibility = View.VISIBLE
            handleShutterFocus(pressed = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isCameraKey(keyCode)) {
            handleShutterFocus(pressed = false)
            handleShutterClick()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun updateControls() {
        var shutterResId = R.drawable.btn_camera_shutter_holo
        if (shutterMode == ShutterMode.VIDEO) {
            shutterResId = if (videoRecorder == null) R.drawable.btn_video_shutter_holo
                           else R.drawable.btn_video_shutter_recording_holo
        }
        binding.cameraActionButton.setImageResource(shutterResId)

        binding.toggleVideoButton.setImageResource(when(shutterMode) {
            ShutterMode.IMAGE -> R.drawable.ic_photo_camera_white_36dp
            ShutterMode.VIDEO -> R.drawable.ic_videocam_white_36dp
        })

        binding.switchCameraButton.setImageResource(
                if (cameraSelector.isSelectedCameraFrontFacing())
                    R.drawable.ic_camera_front_white_36dp
                else R.drawable.ic_camera_rear_white_36dp)

        binding.switchResolutionButton.alpha =
                if (preferredImageSize == ImageSize.FULL_SCREEN) 1.0f else 0.5f
    }

    private fun updateLayout(isPortrait: Boolean) {
        Log.i(TAG, "updateLayout: ${isPortrait}")
        layoutIsPortrait = isPortrait
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        val layoutWidth = if (isPortrait) match else wrap
        val layoutHeight = if (isPortrait) wrap else match
        val orientation = if (isPortrait) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val direction =
                if (isPortrait) LinearLayout.LAYOUT_DIRECTION_RTL
                else LinearLayout.LAYOUT_DIRECTION_LTR

        run {
            val params = FrameLayout.LayoutParams(layoutWidth, layoutHeight)
            params.gravity = if (isPortrait) Gravity.TOP else Gravity.LEFT
            binding.leftTopControlBar.layoutParams = params
            binding.leftTopControlBar.orientation = orientation
            binding.leftTopControlBar.layoutDirection = direction
        }
        run {
            val params = FrameLayout.LayoutParams(layoutWidth, layoutHeight)
            params.gravity = if (isPortrait) Gravity.BOTTOM else Gravity.RIGHT
            binding.rightBottomControlBar.layoutParams = params
            binding.rightBottomControlBar.orientation = orientation
            binding.rightBottomControlBar.layoutDirection = direction
        }
        run {
            val params = FrameLayout.LayoutParams(match, match)
            val metrics = resources.displayMetrics
            val shutterMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 72f, metrics)
            val iconMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 54f, metrics)
            params.topMargin = if (isPortrait) iconMargin.toInt() else 0
            params.bottomMargin = if (isPortrait) shutterMargin.toInt() else 0
            params.leftMargin = if (isPortrait) 0 else iconMargin.toInt()
            params.rightMargin = if (isPortrait) 0 else shutterMargin.toInt()
            binding.editSchemeView.layoutParams = params
        }
    }

    private fun isPortraitOrientation(): Boolean {
        return binding.overlayView.height > binding.overlayView.width
    }

    private fun targetCameraImageSize(): Size {
        val ds = getLandscapeDisplaySize(this)
        return when (preferredImageSize) {
            ImageSize.FULL_SCREEN -> ds
            ImageSize.HALF_SCREEN -> Size(ds.width / 2, ds.height / 2)
            ImageSize.VIDEO_RECORDING -> Size(640, 360)
            ImageSize.EFFECT_GRID -> Size(ds.width / 4, ds.height / 4)
        }
    }

    private fun cameraImageSizeForSavedPicture() = Size(1920, 1080)

    private fun previewImageSizeFromPrefs() =
            if (preferences.useHighQualityPreview()) ImageSize.FULL_SCREEN
            else ImageSize.HALF_SCREEN

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PermissionsChecker.CAMERA_REQUEST_CODE -> {
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
        val hasCamera = PermissionsChecker.hasCameraPermission(this)
        if (hasCamera) {
            restartCameraImageGenerator()
        }
        if (!askedForPermissions && !hasCamera) {
            askedForPermissions = true
            PermissionsChecker.requestCameraPermissions(this)
        }
    }

    private fun handleGeneratedBitmap(pb: ProcessedBitmap) {
        handler.post(fun() {
            if (lastBitmapTimestamp > pb.sourceImage.timestamp) {
                return
            }
            renderTimeStats.addValue(pb.metadata.generationDurationNanos)
            lastBitmapTimestamp = pb.sourceImage.timestamp
            binding.overlayView.generationTimeAverageNanos = renderTimeStats.getAverage()
            binding.overlayView.updateBitmap(pb, showDebugInfo)
            // Save image or video frame if necessary.
            if (pb.sourceImage.status == CameraStatus.CAPTURING_PHOTO) {
                saveImage(pb)
            }
            if (pb.sourceImage.status == CameraStatus.CAPTURING_VIDEO) {
                recordVideoFrame(pb)
            }
        })
    }

    private fun saveImage(pb: ProcessedBitmap) {
        Log.i(TAG, "Saving picture")
        // This can take a while, so show a spinner. Should it allow the user to cancel?
        val saveIndicator = ProgressDialog(this)
        saveIndicator.isIndeterminate = true
        saveIndicator.setMessage(getString(R.string.savingImageMessage))
        saveIndicator.setCancelable(false)
        saveIndicator.show()
        (Thread {
            try {
                val photoId = photoLibrary.savePhoto(this, pb)
                saveIndicator.dismiss()
                handler.post {
                    ViewImageActivity.startActivityWithImageId(this, photoId)
                }
                // Write the PNG in the background since it's slower.
                photoLibrary.writePngImage(this, pb, photoId)
            }
            catch (ex: Exception) {
                Log.w(TAG, "Error saving photo: ${ex}")
                saveIndicator.dismiss()
            }
        }).start()
    }

    private fun recordVideoFrame(pb: ProcessedBitmap) {
        val vr = videoRecorder
        if (vr != null) {
            val source = pb.sourceImage
            if (videoFrameMetadata == null) {
                videoFrameMetadata = MediaMetadata(
                        MediaType.VIDEO,
                        currentEffect!!.effectMetadata(),
                        source.width(),
                        source.height(),
                        source.orientation,
                        source.timestamp)
            }
            vr.recordFrame(source.timestamp, listOf(
                source.getYBytes(), source.getUBytes(), source.getVBytes()
            ))
        }
    }

    private fun effectFromPreferences(): Effect {
        return preferences.effect(
                {effectRegistry.defaultEffectAtIndex(0, preferences.lookupFunction)})
    }

    private fun handleAllocationFromCamera(imageFromCamera: CameraImage) {
        handler.post(fun() {
            // Add fields that the image generator doesn't have. Might be better to have a separate
            // class that holds a CameraImage, display size, and portrait flag.
            val ds = getLandscapeDisplaySize(this)
            val orientation = imageFromCamera.orientation.withPortrait(isPortraitOrientation())
            val cameraImage = imageFromCamera.copy(displaySize=ds, orientation=orientation)
            if (cameraImage.status == CameraStatus.CAPTURING_PHOTO) {
                Log.i(TAG, "Restarting preview capture")
                restartCameraImageGenerator()
            }
            this.imageProcessor.queueCameraImage(cameraImage)
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
        cameraImageGenerator.stop({
            handler.post({
                cameraImageGenerator = cameraSelector.createImageGenerator()
                restartCameraImageGenerator()
                updateControls()
            })
        })
    }

    private fun switchResolution(view: View) {
        if (cameraImageGenerator.status != CameraStatus.CAPTURING_PREVIEW) {
            Log.i(TAG, "Status is ${cameraImageGenerator.status}, not switching resolution")
            return
        }
        if (inEffectSelectionMode) {
            return
        }
        preferredImageSize =
                if (preferredImageSize == ImageSize.FULL_SCREEN) ImageSize.HALF_SCREEN
                else ImageSize.FULL_SCREEN
        preferences.setUseHighQualityPreview(preferredImageSize == ImageSize.FULL_SCREEN)
        restartCameraImageGenerator()
        updateControls()
    }

    // Override back navigation handling only if the effect grid is visible.
    private fun updateInEffectSelectionModeFlag(inMode: Boolean) {
        inEffectSelectionMode = inMode
        onBackPressedCallback.isEnabled = inMode
    }

    private fun toggleEffectSelectionMode(view: View?) {
        if (videoRecorder != null) {
            Log.i(TAG, "Video recording in progress, not toggling effect grid")
            return
        }
        updateInEffectSelectionModeFlag(!inEffectSelectionMode)
        if (!cameraImageGenerator.status.isCapturing()) {
            Log.i(TAG, "Status is ${cameraImageGenerator.status}, not toggling effect grid")
            return
        }
        if (inEffectSelectionMode) {
            previousEffect = currentEffect
            val comboEffects = effectRegistry.defaultEffectFunctions(
                    preferences.lookupFunction, EffectContext.COMBO_GRID)
            currentEffect = CombinationEffect(comboEffects, 50)
            preferredImageSize = ImageSize.EFFECT_GRID
            binding.controlLayout.visibility = View.GONE
            Log.i(TAG, "Showing combo grid")
        }
        else {
            currentEffect = previousEffect
            preferredImageSize = previewImageSizeFromPrefs()
            Log.i(TAG, "Exiting combo grid")
        }
        restartCameraImageGenerator()
        imageProcessor.start(currentEffect!!, this::handleGeneratedBitmap)
        binding.controlLayout.visibility = if (inEffectSelectionMode) View.GONE else View.VISIBLE
        binding.editSchemeView.visibility = View.GONE
    }

    private fun handleOverlayViewTouchEvent(view: OverlayView, event: MotionEvent) {
        if (event.pointerCount > 1 && !inEffectSelectionMode) {
            // Zoom in or out if we've gotten repeated multitouch events.
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(1) - event.getY(1)
            val dist = Math.hypot(dx.toDouble(), dy.toDouble())
            if (previousFingerSpacing <= 0) {
                previousFingerSpacing = dist
            }
            else if (dist > previousFingerSpacing + 2) {
                cameraImageGenerator.zoomIn(0.025)
                previousFingerSpacing = dist
            }
            else if (dist < previousFingerSpacing - 2) {
                cameraImageGenerator.zoomIn(-0.025)
                previousFingerSpacing = dist
            }
        }
        else {
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (!inEffectSelectionMode) {
                    binding.controlLayout.visibility =
                            if (binding.controlLayout.visibility == View.VISIBLE) View.GONE
                            else View.VISIBLE
                }
            }
        }
        if (event.action == MotionEvent.ACTION_UP) {
            previousFingerSpacing = 0.0
            // Handle effect selection with ACTION_UP rather than ACTION_DOWN so that
            // it won't be inadvertently triggered at the start of a back gesture.
            if (inEffectSelectionMode) {
                handleEffectGridTouch(view, event)
            }
        }
    }

    private fun handleEffectGridTouch(view: View, event: MotionEvent) {
        if (!cameraImageGenerator.status.isCapturing()) {
            Log.i(TAG, "Status is ${cameraImageGenerator.status}, not selecting effect")
            return
        }
        val gridSize = Math.ceil(
                Math.sqrt(effectRegistry.defaultEffectCount().toDouble())).toInt()
        val tileWidth = view.width / gridSize
        val tileHeight = view.height / gridSize
        val tileX = (event.x / tileWidth).toInt()
        val tileY = (event.y / tileHeight).toInt()
        var index = gridSize * tileY + tileX
        index = Math.min(Math.max(0, index), effectRegistry.defaultEffectCount() - 1)
        effectIndex = index
        Log.i(TAG, "Selected effect ${index}")

        val eff = effectRegistry.defaultEffectAtIndex(index, preferences.lookupFunction)
        preferences.saveEffectInfo(eff.effectName(), eff.effectParameters())
        updateInEffectSelectionModeFlag(false)
        binding.overlayView.visibility = View.VISIBLE
        binding.controlLayout.visibility = View.VISIBLE
        preferredImageSize = previewImageSizeFromPrefs()
        restartCameraImageGenerator()
        imageProcessor.start(currentEffect!!, this::handleGeneratedBitmap)

        if (eff is CustomEffect) {
            binding.editSchemeView.setScheme(eff.colorScheme)
            binding.editSchemeView.visibility = View.VISIBLE
            customSchemeId = eff.customSchemeId
        }
        else {
            customSchemeId = ""
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
            binding.cameraActionButton.setImageResource(resId)
        }
        else {
            var resId = R.drawable.btn_camera_shutter_holo
            if (shutterMode == ShutterMode.VIDEO) {
                resId = if (videoRecorder != null)
                    R.drawable.btn_video_shutter_recording_holo
                else R.drawable.btn_video_shutter_holo
            }
            binding.cameraActionButton.setImageResource(resId)
        }
    }

    private fun handleCustomColorSchemeChanged(cs: CustomColorScheme) {
        if (customSchemeId.isEmpty()) {
            return
        }
        // The order matters here because `defaultEffectAtIndex` reads from the preferences.
        preferences.saveCustomScheme(customSchemeId, cs)
        // Keeping customSchemeId and effectIndex as instance variables is ugly. The problem is that
        // when the user selects a custom effect, `currentEffect` gets set to the underlying effect
        // rather than the "wrapper" CustomEffect.
        val newEffect = effectRegistry.defaultEffectAtIndex(
                effectIndex, preferences.lookupFunction)
        // Save the resulting effect so that it will restore correctly.
        preferences.saveEffectInfo(newEffect.effectName(), newEffect.effectParameters())
        restartCameraImageGenerator()
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
        VCPreferencesActivity.startIntent(this)
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
                    Log.i(TAG, "Wrote video frame: " + recorder.frameTimestamps.size)
                }
                VideoRecorder.Status.FINISHED -> {
                    Log.i(TAG, "Video recording stopped, writing to library")
                    preferredImageSize = previewImageSizeFromPrefs()
                    if (this.hasWindowFocus()) {
                        restartCameraImageGenerator()
                    }
                    val metadata = this.videoFrameMetadata
                    if (metadata != null) {
                        photoLibrary.saveVideo(
                                this,
                                recorder.videoId,
                                metadata,
                                recorder.frameTimestamps,
                                audioStartTimestamp)
                        ViewVideoActivity.startActivityWithVideoId(this, recorder.videoId)
                    }
                }
                else -> {}
            }
        }
    }

    // This function is no longer needed now that the app is fully migrated to scoped storage.
    // Keeping for historical purposes.
    /*
    private fun migratePhotoLibraryIfNeeded() {
        if (libraryMigrationDone) {
            return
        }
        val needsMigration = PhotoLibrary.shouldMigrateToPrivateStorage(this)
        if (!needsMigration) {
            libraryMigrationDone = true
            return
        }
        else {
            handler.post {
                val migrationSpinner = ProgressDialog(this)
                migrationSpinner.isIndeterminate = true
                migrationSpinner.setMessage("Moving library...")
                migrationSpinner.setCancelable(false)
                migrationSpinner.show()

                Thread {
                    var numFiles = 0
                    var totalBytes = 0L
                    var migrationError: Exception? = null
                    try {
                        PhotoLibrary.migrateToPrivateStorage(this) {fileSize ->
                            handler.post {
                                numFiles += 1
                                totalBytes += fileSize
                                val mb = String.format("%.1f", totalBytes / 1e6)
                                val msg = "Moving library:\nProcessed $numFiles files, ${mb}MB";
                                migrationSpinner.setMessage(msg)
                            }
                        }
                        libraryMigrationDone = true
                        Log.i(TAG, "Migration succeeded")
                        if (PhotoLibrary.shouldMigrateToPrivateStorage(this)) {
                            Log.i(TAG, "Hmm, but previous library is still there?")
                        }
                    }
                    catch (ex: Exception) {
                        Log.e(TAG, "Migration failed", ex)
                        migrationError = ex
                    }
                    handler.post {
                        migrationSpinner.hide()
                        val finishedMsg = if (libraryMigrationDone)
                            """
                                Your Vector Camera library has been moved to private storage.
                                This is necessary to support Android 11. You shouldn't notice any
                                difference, but be aware that your library will be deleted if you
                                uninstall the app.
                            """.trimIndent().replace(System.lineSeparator(), " ")
                        else
                            """
                                There was an error moving your Vector Camera library to private
                                storage (necessary to support Android 11). If this persists,
                                contact bnenning@gmail.com.
                            """.trimIndent().replace(System.lineSeparator(), " ") +
                                    "\n\nThe error was:\n$migrationError"
                        AlertDialog.Builder(this)
                            .setMessage(finishedMsg)
                            .setPositiveButton("OK", null)
                            .show()

                    }
                }.start()
            }
        }
    }
    */

    companion object {
        const val TAG = "MainActivity"

        const val ACTIVITY_CHOOSE_PICTURE = 1
    }
}
