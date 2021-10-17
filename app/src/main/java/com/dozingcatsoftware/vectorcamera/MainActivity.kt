package com.dozingcatsoftware.vectorcamera

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.renderscript.RenderScript
import android.util.Log
import android.util.Size
import android.util.TypedValue
import androidx.preference.PreferenceManager
import com.dozingcatsoftware.util.getLandscapeDisplaySize
import kotlinx.android.synthetic.main.activity_main.*
import java.io.FileOutputStream
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var rs: RenderScript
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

    private var libraryMigrationDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        photoLibrary = PhotoLibrary.defaultLibrary(this)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)
        PreferenceManager.setDefaultValues(this.baseContext, R.xml.preferences, false)

        // Use PROFILE type only on first run?
        rs = RenderScript.create(this, RenderScript.ContextType.NORMAL)
        imageProcessor = CameraImageProcessor(rs)

        currentEffect = effectFromPreferences()
        preferredImageSize =
                if (preferences.useHighQualityPreview()) ImageSize.FULL_SCREEN
                else ImageSize.HALF_SCREEN

        cameraSelector = CameraSelector(this)
        cameraImageGenerator = cameraSelector.createImageGenerator(rs)

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
        editSchemeView.activity = this
        editSchemeView.changeCallback = this::handleCustomColorSchemeChanged

        // Preload the effect classes so there's not a delay when switching to the effect grid.
        Thread({
            Log.i(TAG, "Starting effect loading thread")
            for (i in 0 until effectRegistry.defaultEffectCount()) {
                effectRegistry.defaultEffectAtIndex(
                    i, rs, preferences.lookupFunction, EffectContext.PRELOAD)
            }
            Log.i(TAG, "Done loading effects")
        }).start()
        updateControls()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndStartCamera()
        overlayView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE

        // View size is zero in onResume, have to wait for layout notification.
        var listener: ViewTreeObserver.OnGlobalLayoutListener? = null
        listener = ViewTreeObserver.OnGlobalLayoutListener {
            updateLayout(isPortraitOrientation())
            overlayView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        overlayView.viewTreeObserver.addOnGlobalLayoutListener(listener)
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

    private fun updateControls() {
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

        switchResolutionButton.alpha =
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
            leftTopControlBar.layoutParams = params
            leftTopControlBar.orientation = orientation
            leftTopControlBar.layoutDirection = direction
        }
        run {
            val params = FrameLayout.LayoutParams(layoutWidth, layoutHeight)
            params.gravity = if (isPortrait) Gravity.BOTTOM else Gravity.RIGHT
            rightBottomControlBar.layoutParams = params
            rightBottomControlBar.orientation = orientation
            rightBottomControlBar.layoutDirection = direction
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
            editSchemeView.layoutParams = params
        }
    }

    private fun isPortraitOrientation(): Boolean {
        return overlayView.height > overlayView.width
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
        when (requestCode) {
            PermissionsChecker.CAMERA_AND_STORAGE_REQUEST_CODE -> {
                if (PermissionsChecker.hasCameraPermission(this)) {
                    cameraImageGenerator.start(
                            CameraStatus.CAPTURING_PREVIEW,
                            this.targetCameraImageSize(),
                            this::handleAllocationFromCamera)
                }
                if (PermissionsChecker.hasStoragePermission(this)) {
                    Log.i(TAG, "BBB: Migrating library")
                    migratePhotoLibraryIfNeeded()
                }
            }
        }
    }

    private fun checkPermissionAndStartCamera() {
        val hasCamera = PermissionsChecker.hasCameraPermission(this)
        val hasStorage = PermissionsChecker.hasStoragePermission(this)
        if (hasCamera && hasStorage) {
            restartCameraImageGenerator()
            Log.i(TAG, "AAA: Migrating library")
            migratePhotoLibraryIfNeeded()
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
                saveImage(pb)
            }
            if (pb.sourceImage.status == CameraStatus.CAPTURING_VIDEO) {
                recordVideoFrame(pb)
            }
        })
    }

    private fun saveImage(pb: ProcessedBitmap) {
        Log.i(TAG, "Saving picture")
        if (pb.yuvBytes == null) {
            Log.w(TAG, "yuvBytes not set for saved image")
        }
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

    private fun effectFromPreferences(): Effect {
        return preferences.effect(rs,
                {effectRegistry.defaultEffectAtIndex(0, rs, preferences.lookupFunction)})
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
                cameraImageGenerator = cameraSelector.createImageGenerator(rs)
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

    private fun toggleEffectSelectionMode(view: View?) {
        if (videoRecorder != null) {
            Log.i(TAG, "Video recording in progress, not toggling effect grid")
            return
        }
        inEffectSelectionMode = !inEffectSelectionMode
        if (!cameraImageGenerator.status.isCapturing()) {
            Log.i(TAG, "Status is ${cameraImageGenerator.status}, not toggling effect grid")
            return
        }
        if (inEffectSelectionMode) {
            previousEffect = currentEffect
            val comboEffects = effectRegistry.defaultEffectFunctions(
                    rs, preferences.lookupFunction, EffectContext.COMBO_GRID)
            currentEffect = CombinationEffect(comboEffects, 50)
            preferredImageSize = ImageSize.EFFECT_GRID
            controlLayout.visibility = View.GONE
            Log.i(TAG, "Showing combo grid")
        }
        else {
            currentEffect = previousEffect
            preferredImageSize = previewImageSizeFromPrefs()
            Log.i(TAG, "Exiting combo grid")
        }
        restartCameraImageGenerator()
        imageProcessor.start(currentEffect!!, this::handleGeneratedBitmap)
        controlLayout.visibility = if (inEffectSelectionMode) View.GONE else View.VISIBLE
        editSchemeView.visibility = View.GONE
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
                if (inEffectSelectionMode) {
                    handleEffectGridTouch(view, event)
                }
                else {
                    controlLayout.visibility =
                            if (controlLayout.visibility == View.VISIBLE) View.GONE
                            else View.VISIBLE
                }
            }
        }
        if (event.action == MotionEvent.ACTION_UP) {
            previousFingerSpacing = 0.0
        }
    }

    private fun handleEffectGridTouch(view: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (!cameraImageGenerator.status.isCapturing()) {
                Log.i(TAG, "Status is ${cameraImageGenerator.status}, not selecting effect")
                return true
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

            val eff = effectRegistry.defaultEffectAtIndex(index, rs, preferences.lookupFunction)
            preferences.saveEffectInfo(eff.effectName(), eff.effectParameters())
            inEffectSelectionMode = false
            overlayView.visibility = View.VISIBLE
            controlLayout.visibility = View.VISIBLE
            preferredImageSize = previewImageSizeFromPrefs()
            restartCameraImageGenerator()
            imageProcessor.start(currentEffect!!, this::handleGeneratedBitmap)

            if (eff is CustomEffect) {
                editSchemeView.setScheme(eff.colorScheme)
                editSchemeView.visibility = View.VISIBLE
                customSchemeId = eff.customSchemeId
            }
            else {
                customSchemeId = ""
            }
            return true
        }
        return false
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
        if (!PermissionsChecker.hasStoragePermission(this)) {
            PermissionsChecker.requestStoragePermissionsToTakePhoto(this)
            return
        }
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
                effectIndex, rs, preferences.lookupFunction)
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
        if (!PermissionsChecker.hasStoragePermission(this)) {
            PermissionsChecker.requestStoragePermissionsToGoToLibrary(this)
            return
        }
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
            }
        }
    }

    companion object {
        const val TAG = "MainActivity"

        const val ACTIVITY_CHOOSE_PICTURE = 1
    }
}
