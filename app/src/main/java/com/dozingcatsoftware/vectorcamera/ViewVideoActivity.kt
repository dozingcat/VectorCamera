package com.dozingcatsoftware.vectorcamera

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler

import androidx.core.content.FileProvider
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.dozingcatsoftware.vectorcamera.effect.CustomColorMapEffect
import com.dozingcatsoftware.vectorcamera.effect.CustomContourEffect
import com.dozingcatsoftware.vectorcamera.effect.CustomPaletteEffect
import com.dozingcatsoftware.vectorcamera.effect.CustomPermuteEffect
import com.dozingcatsoftware.vectorcamera.effect.Effect
import com.dozingcatsoftware.vectorcamera.effect.EffectContext
import com.dozingcatsoftware.vectorcamera.effect.EffectInfo
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry
import com.dozingcatsoftware.vectorcamera.effect.customColorMapEffect
import com.dozingcatsoftware.vectorcamera.effect.customContourEffect
import com.dozingcatsoftware.vectorcamera.effect.customPaletteEffect
import com.dozingcatsoftware.vectorcamera.effect.customPermuteEffect
import com.dozingcatsoftware.util.getLandscapeDisplaySize
import com.dozingcatsoftware.util.grantUriPermissionForIntent
import com.dozingcatsoftware.util.scanSavedMediaFile
import com.dozingcatsoftware.vectorcamera.databinding.ViewVideoBinding
import java.io.File


// Ways videos can be exported, and the messages shown in the export progress dialog for each.
internal enum class ExportType private constructor(
        val id: String,
        val mimeType: String,
        val fileExtension: String,
        val exportedFile: (PhotoLibrary, String) -> File,
        val exportDialogTitleId: Int,
        val exportDialogMessageVideoId: Int,
        val exportDialogMessageAudioId: Int) {
    WEBM("webm", "video/webm", "webm",
            {library: PhotoLibrary, videoId: String -> library.videoFileForItemId(videoId)},
            R.string.webmExportDialogTitle,
            R.string.webmExportDialogMessageVideo,
            R.string.webmExportDialogMessageAudio),
    ZIP("zip", "application/zip", "zip",
            {library: PhotoLibrary, videoId: String -> library.videoFramesArchiveForItemId(videoId)},
            R.string.zipExportDialogTitle,
            R.string.zipExportDialogMessageVideo,
            R.string.zipExportDialogMessageAudio),
}

class ViewVideoActivity: AppCompatActivity() {
    private lateinit var binding: ViewVideoBinding

    private lateinit var photoLibrary: PhotoLibrary

    private lateinit var videoId: String
    private var inEffectSelectionMode = false
    private var thumbnailRenderer: StaticThumbnailRenderer? = null
    private val effectRegistry = EffectRegistry()
    private lateinit var videoReader: VideoReader
    private val preferences = VCPreferences(this)
    private val handler = Handler()

    var timeFn = System::currentTimeMillis
    private var frameIndex = 0
    private var isPlaying = false
    private var playbackStartFrame = 0
    private var playbackStartTimestamp = 0L
    private var audioPlayer: AudioPlayer? = null

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        photoLibrary = PhotoLibrary.defaultLibrary(this)

        binding.shareButton.setOnClickListener(this::doShare)
        binding.switchEffectButton.setOnClickListener(this::toggleEffectSelectionMode)
        binding.editEffectButton.setOnClickListener(this::showCustomEffectEditor)
        binding.editSchemeView.activity = this
        binding.editSchemeView.changeCallback = this::handleCustomColorSchemeChanged
        binding.editSchemeView.doneCallback = this::handleCustomEffectEditorDone
        binding.editPermuteView.changeCallback = this::handleCustomPermuteSchemeChanged
        binding.editPermuteView.doneCallback = this::handleCustomEffectEditorDone
        binding.editPaletteView.activity = this
        binding.editPaletteView.changeCallback = this::handleCustomPaletteSchemeChanged
        binding.editPaletteView.doneCallback = this::handleCustomEffectEditorDone
        binding.editContourView.activity = this
        binding.editContourView.changeCallback = this::handleCustomContourSchemeChanged
        binding.editContourView.doneCallback = this::handleCustomEffectEditorDone
        binding.playPauseButton.setOnClickListener(this::togglePlay)
        binding.deleteButton.setOnClickListener(this::deleteVideo)
        binding.effectPickerView.setEffects(effectRegistry.effectInfos)
        binding.effectPickerView.onEffectSelected = this::handleEffectSelected
        binding.effectPickerView.onThumbnailNeeded = {info ->
            thumbnailRenderer?.requestThumbnail(info.id)
        }

        // Yes, this does I/O.
        videoId = intent.getStringExtra("videoId")!!
        videoReader = VideoReader(photoLibrary, videoId, getLandscapeDisplaySize(this))

        binding.frameSeekBar.max = videoReader.numberOfFrames() - 1
        binding.frameSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    stopPlaying()
                    loadFrame(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        loadFrame(0)

        // Back closes the effect picker or an open editor panel rather than leaving the activity.
        onBackPressedCallback = object: OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (inEffectSelectionMode) {
                    toggleEffectSelectionMode(null)
                }
                else if (visibleCustomEffectEditor() != null) {
                    hideCustomEffectEditor()
                }
            }
        }
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
        updateEditButton()

        val audioFile = photoLibrary.rawAudioRandomAccessFileForItemId(videoId)
        if (audioFile != null) {
            audioPlayer = AudioPlayer(audioFile)
        }
    }

    public override fun onPause() {
        stopPlaying()
        super.onPause()
    }

    override fun onDestroy() {
        thumbnailRenderer?.shutdown()
        thumbnailRenderer = null
        super.onDestroy()
    }

    private fun updateControls() {
        binding.frameSeekBar.progress = frameIndex
        binding.playPauseButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause_white_36dp
                else R.drawable.ic_play_arrow_white_36dp)
    }

    private fun loadFrame(index: Int) {
        frameIndex = index
        val bitmap = videoReader.bitmapForFrame(index)
        binding.overlayView.updateBitmap(bitmap)
        binding.overlayView.invalidate()
    }

    private fun updateInEffectSelectionModeFlag(inMode: Boolean) {
        inEffectSelectionMode = inMode
        updateBackPressedCallback()
    }

    private fun updateBackPressedCallback() {
        onBackPressedCallback.isEnabled =
                inEffectSelectionMode || visibleCustomEffectEditor() != null
    }

    // The video's effect as an editable custom effect, or null if it isn't one.
    private fun customEffectForVideo(metadata: MediaMetadata): Effect? =
            effectRegistry.customEffectForMetadata(
                    metadata.effectId, metadata.effectMetadata, preferences.lookupFunction)

    private fun customEffectEditors() = listOf(
            binding.editSchemeView, binding.editPermuteView,
            binding.editPaletteView, binding.editContourView)

    private fun visibleCustomEffectEditor(): View? =
            customEffectEditors().firstOrNull {it.visibility == View.VISIBLE}

    // The edit button shows when the video uses a custom effect and its editor is closed.
    private fun updateEditButton() {
        val editable = customEffectForVideo(photoLibrary.metadataForItemId(videoId)) != null
        binding.editEffectButton.visibility =
                if (editable && visibleCustomEffectEditor() == null) View.VISIBLE else View.GONE
    }

    // Opens the editor panel for `effect` if it's a custom effect; otherwise closes any panel.
    // Edits change this video's saved effect parameters, not the custom effect's preferences.
    private fun showCustomEffectEditor(effect: Effect) {
        hideCustomEffectEditor()
        when (effect) {
            is CustomColorMapEffect -> {
                binding.editSchemeView.setScheme(effect.scheme)
                binding.editSchemeView.visibility = View.VISIBLE
            }
            is CustomPermuteEffect -> {
                binding.editPermuteView.setScheme(effect.scheme)
                binding.editPermuteView.visibility = View.VISIBLE
            }
            is CustomPaletteEffect -> {
                binding.editPaletteView.setScheme(effect.scheme)
                binding.editPaletteView.visibility = View.VISIBLE
            }
            is CustomContourEffect -> {
                binding.editContourView.setScheme(effect.scheme)
                binding.editContourView.visibility = View.VISIBLE
            }
            else -> {}
        }
        updateBackPressedCallback()
        updateEditButton()
    }

    // Opens the editor for the video's custom effect, from the edit button.
    private fun showCustomEffectEditor(view: View?) {
        val effect = customEffectForVideo(photoLibrary.metadataForItemId(videoId)) ?: return
        showCustomEffectEditor(effect)
    }

    private fun hideCustomEffectEditor() {
        for (editor in customEffectEditors()) {
            editor.visibility = View.GONE
        }
        handleCustomEffectEditorDone()
    }

    private fun handleCustomEffectEditorDone() {
        updateBackPressedCallback()
        updateEditButton()
    }

    private fun handleCustomColorSchemeChanged(cs: CustomColorScheme) {
        applyEditedEffect {id -> customColorMapEffect(cs, id, EffectContext.NORMAL)}
    }

    private fun handleCustomPermuteSchemeChanged(ps: CustomPermuteScheme) {
        applyEditedEffect {id -> customPermuteEffect(ps, id)}
    }

    private fun handleCustomPaletteSchemeChanged(ps: CustomPaletteScheme) {
        applyEditedEffect {id -> customPaletteEffect(ps, id)}
    }

    private fun handleCustomContourSchemeChanged(cs: CustomContourScheme) {
        applyEditedEffect {id -> customContourEffect(cs, id)}
    }

    // Switches the video to the edited effect and saves it to the video's metadata and
    // thumbnail, keeping the picker ID so the picker still highlights the custom effect.
    private fun applyEditedEffect(effectForId: (String) -> Effect) {
        val metadata = photoLibrary.metadataForItemId(videoId)
        val effectId = metadata.effectId ?: return
        saveEffect(effectForId(effectId), effectId)
    }

    // Makes `effect` the video's effect, in the reader and in the saved metadata and thumbnail.
    private fun saveEffect(effect: Effect, effectId: String?) {
        videoReader.effect = effect
        val newMetadata = photoLibrary.metadataForItemId(videoId)
                .withEffectMetadata(effect.effectMetadata(), effectId)
        val firstFrame = videoReader.bitmapForFrame(0)
        photoLibrary.writeMetadata(newMetadata, videoId)
        photoLibrary.writeThumbnail(firstFrame, videoId)
        loadFrame(frameIndex)
    }

    private fun toggleEffectSelectionMode(view: View?) {
        updateInEffectSelectionModeFlag(!inEffectSelectionMode)
        if (inEffectSelectionMode) {
            showEffectPicker()
        }
        else {
            hideEffectPicker()
        }
    }

    // Shows thumbnails of the current frame with each effect applied.
    private fun showEffectPicker() {
        stopPlaying()
        val picker = binding.effectPickerView
        var renderer: StaticThumbnailRenderer? = null
        renderer = StaticThumbnailRenderer(
                effectRegistry, preferences.lookupFunction,
                videoReader.cameraImageForFrame(frameIndex),
                {picker.thumbnailSize},
                {id, bitmap ->
                    handler.post {
                        // Ignore thumbnails that arrive after the picker was closed.
                        if (renderer === thumbnailRenderer) {
                            picker.setThumbnail(id, bitmap)
                        }
                    }
                })
        thumbnailRenderer?.shutdown()
        thumbnailRenderer = renderer
        picker.clearThumbnails()
        picker.setSourceShape(
                videoReader.landscapeVideoWidth(), videoReader.landscapeVideoHeight(),
                videoReader.isPortrait())
        val effectId = photoLibrary.metadataForItemId(videoId).effectId
        picker.selectedEffectId = effectId
        picker.visibility = View.VISIBLE
        picker.scrollToEffect(effectId)
        binding.controlBar.visibility = View.GONE
        hideCustomEffectEditor()
    }

    private fun hideEffectPicker() {
        thumbnailRenderer?.shutdown()
        thumbnailRenderer = null
        binding.effectPickerView.visibility = View.GONE
        binding.effectPickerView.clearThumbnails()
        binding.controlBar.visibility = View.VISIBLE
    }

    private fun togglePlay(view: View) {
        if (isPlaying) {
            stopPlaying()
        }
        else {
            startPlaying()
        }
    }

    private fun startPlaying() {
        isPlaying = true
        if (frameIndex >= videoReader.numberOfFrames() - 1) {
            showFrame(0, videoReader.bitmapForFrame(0))
        }
        playbackStartFrame = frameIndex
        playbackStartTimestamp = timeFn()

        audioPlayer?.startFromMillisOffset( videoReader.millisBetweenFrames(0, frameIndex))

        scheduleNextFrame()
        updateControls()
    }

    private fun stopPlaying() {
        isPlaying = false
        audioPlayer?.stop()
        updateControls()
    }

    private fun millisSincePlaybackStart() = timeFn() - playbackStartTimestamp

    private fun scheduleNextFrame() {
        if (!isPlaying) {
            return
        }
        if (frameIndex >= videoReader.numberOfFrames() - 1) {
            stopPlaying()
            return
        }
        val nextFrameIndex = videoReader.nextFrameIndexForTimeDelta(
                playbackStartFrame, millisSincePlaybackStart())
        val nextFrameBitmap = videoReader.bitmapForFrame(nextFrameIndex)
        val delay = videoReader.millisBetweenFrames(playbackStartFrame, nextFrameIndex) -
                millisSincePlaybackStart()
        // Log.i(TAG, "Next frame index: ${nextFrameIndex} delay: ${delay}")
        handler.postDelayed({
            showFrame(nextFrameIndex, nextFrameBitmap)
            handler.post(this::scheduleNextFrame)
        }, maxOf(delay, 1))
    }

    private fun showFrame(index: Int, pb: ProcessedBitmap) {
        if (isPlaying) {
            frameIndex = index
            binding.overlayView.updateBitmap(pb)
            updateControls()
        }
    }

    private fun handleEffectSelected(info: EffectInfo) {
        val effect = effectRegistry.createEffect(info.id, preferences.lookupFunction)
        saveEffect(effect, info.id)
        updateInEffectSelectionModeFlag(false)
        hideEffectPicker()
        // A newly chosen custom effect opens its editor, as in the camera activity.
        showCustomEffectEditor(effect)
    }

    private fun doShare(view: View) {
        stopPlaying()
        val shareTypes = arrayOf("webm", "zip", "frame")
        var selectedShareType = "webm"
        val shareTypeLabels = arrayOf(
                getString(R.string.shareVideoWebmOptionLabel),
                getString(R.string.shareVideoFrameArchiveOptionLabel),
                getString(R.string.shareVideoSingleFrameOptionLabel)
        )
        AlertDialog.Builder(this)
                .setTitle(R.string.shareVideoDialogTitle)
                .setSingleChoiceItems(shareTypeLabels, 0, {
                    _: DialogInterface, which: Int -> selectedShareType = shareTypes[which]
                })
                .setPositiveButton(R.string.shareDialogYesLabel, {_: DialogInterface, _: Int ->
                    when (selectedShareType) {
                        "webm" -> shareVideo(ExportType.WEBM)
                        "zip" -> shareVideo(ExportType.ZIP)
                        "frame" -> shareCurrentFrame()
                    }
                })
                .setNegativeButton(R.string.shareDialogNoLabel, null)
                .show()
    }

    private fun runShareActivity(exportType: ExportType, exportedFile: File) {
        val fileUri = FileProvider.getUriForFile(this,
                BuildConfig.APPLICATION_ID + ".fileprovider", exportedFile)
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = exportType.mimeType
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri)
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "${Constants.APP_NAME} $videoId.${exportType.fileExtension}")
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val chooser = Intent.createChooser(shareIntent, getString(R.string.shareActionTitle))
        grantUriPermissionForIntent(this, fileUri, chooser)
        startActivity(chooser)
    }

    private fun encodeVideo(exportType: ExportType) {
        val progressDialog = ProgressDialog(this)

        fun handleEncodingProgress(progress: ProcessVideoTask.Progress) {
            val message = getString(when (progress.mediaType) {
                ProcessVideoTask.MediaType.AUDIO -> exportType.exportDialogMessageAudioId
                else -> exportType.exportDialogMessageVideoId
            })
            progressDialog.setMessage(message)
            progressDialog.progress = (100 * progress.fractionDone).toInt()
        }

        fun handleEncodingFinished(result: ProcessVideoTask.Result) {
            progressDialog.dismiss()
            if (result.status == ProcessVideoTask.ResultStatus.SUCCEEDED) {
                // If we weren't using private storage, we'd call scanSavedMediaFile on .webm files
                // here so that the video would be visible to other apps.
                // Update metadata so we won't need to regenerate the video if we export again
                // with the same effect.
                val metadata = photoLibrary.metadataForItemId(videoId)
                val newMetadata = metadata.withExportedEffectMetadata(
                        videoReader.effect.effectMetadata(), exportType.id)
                photoLibrary.writeMetadata(newMetadata, videoId)
                runShareActivity(exportType, result.outputFile!!)
            }
            else {
                Toast.makeText(applicationContext, "Encoding failed", Toast.LENGTH_SHORT).show()
            }
        }

        val encodeTask = when (exportType) {
            ExportType.WEBM ->
                CreateWebmAsyncTask(::handleEncodingProgress, ::handleEncodingFinished)
            ExportType.ZIP ->
                CreateVideoZipFileAsyncTask(::handleEncodingProgress, ::handleEncodingFinished)
        }
        encodeTask.execute(ProcessVideoTask.Params(videoReader, photoLibrary, videoId))

        fun handleDialogCanceled(dlg: DialogInterface) {
            encodeTask.cancel(true)
            dlg.dismiss()
        }

        progressDialog.setCancelable(true)
        progressDialog.setOnCancelListener(::handleDialogCanceled)
        progressDialog.setTitle(getString(exportType.exportDialogTitleId))
        progressDialog.setMessage("")
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.setMax(100)
        progressDialog.show()
    }

    private fun shareVideo(exportType: ExportType) {
        stopPlaying()
        val exportedFile = exportType.exportedFile(photoLibrary, videoId)
        val metadata = photoLibrary.metadataForItemId(videoId)
        val exportedEffect = metadata.exportedEffectMetadata[exportType.id]
        val currentEffect = videoReader.effect.effectMetadata()
        // FIXME: The == check sometimes returns false for effects that are the same.
        // Possibly due to keys switching from int to float after JSON serialization.
        if (exportedFile.exists() && currentEffect == exportedEffect) {
            runShareActivity(exportType, exportedFile)
        }
        else {
            encodeVideo(exportType)
        }
    }

    private fun shareCurrentFrame() {
        val pb = videoReader.bitmapForFrame(frameIndex)
        val bitmap = pb.renderBitmap(pb.sourceImage.width(), pb.sourceImage.height())
        val frameFile = photoLibrary.tempFileWithName(videoId + "_frame.png")
        photoLibrary.createTempFileOutputStream(frameFile).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val fileUri = FileProvider.getUriForFile(this,
                BuildConfig.APPLICATION_ID + ".fileprovider", frameFile)
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "image/png"
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri)
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "${Constants.APP_NAME} $videoId.png")
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val chooser = Intent.createChooser(shareIntent, getString(R.string.shareActionTitle))
        grantUriPermissionForIntent(this, fileUri, chooser)
        startActivity(chooser)
    }

    private fun deleteVideo(view: View) {
        val deleteFn = { _: DialogInterface, _: Int ->
            photoLibrary.deleteItem(videoId)
            finish()
        }

        AlertDialog.Builder(this)
                .setCancelable(true)
                .setMessage("Are you sure you want to delete this video?")
                .setPositiveButton("Delete", deleteFn)
                .setNegativeButton("Don't delete", null)
                .show()
    }

    companion object {
        const val TAG = "ViewVideoActivity"

        fun startActivityWithVideoId(parent: Activity, videoId: String): Intent {
            val intent = Intent(parent, ViewVideoActivity::class.java)
            intent.putExtra("videoId", videoId)
            parent.startActivityForResult(intent, 0)
            return intent
        }
    }
}