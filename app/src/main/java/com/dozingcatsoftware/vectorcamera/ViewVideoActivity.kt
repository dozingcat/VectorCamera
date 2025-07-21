package com.dozingcatsoftware.vectorcamera

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler

import androidx.core.content.FileProvider
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.dozingcatsoftware.vectorcamera.effect.CombinationEffect
import com.dozingcatsoftware.vectorcamera.effect.Effect
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry
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
    private var originalEffect: Effect? = null
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
        binding.playPauseButton.setOnClickListener(this::togglePlay)
        binding.deleteButton.setOnClickListener(this::deleteVideo)
        binding.overlayView.touchEventHandler = this::handleOverlayViewTouch

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

        onBackPressedCallback = object: OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (inEffectSelectionMode) {
                    toggleEffectSelectionMode(null)
                }
            }
        }
        onBackPressedDispatcher.addCallback(onBackPressedCallback)

        val audioFile = photoLibrary.rawAudioRandomAccessFileForItemId(videoId)
        if (audioFile != null) {
            audioPlayer = AudioPlayer(audioFile)
        }
    }

    public override fun onPause() {
        stopPlaying()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (inEffectSelectionMode) {
            videoReader.forcePortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
            if (!isPlaying) {
                loadFrame(frameIndex)
            }
        }
    }

    private fun updateControls() {
        binding.frameSeekBar.progress = frameIndex
        binding.playPauseButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause_white_36dp
                else R.drawable.ic_play_arrow_white_36dp)
    }

    private fun isPortraitOrientation(): Boolean {
        return binding.overlayView.height > binding.overlayView.width
    }

    private fun loadFrame(index: Int) {
        frameIndex = index
        val bitmap = videoReader.bitmapForFrame(index)
        binding.overlayView.processedBitmap = bitmap
        binding.overlayView.invalidate()
    }

    private fun updateInEffectSelectionModeFlag(inMode: Boolean) {
        inEffectSelectionMode = inMode
        onBackPressedCallback.isEnabled = inMode
    }

    private fun toggleEffectSelectionMode(view: View?) {
        updateInEffectSelectionModeFlag(!inEffectSelectionMode)
        if (inEffectSelectionMode) {
            originalEffect = videoReader.effect
            videoReader.effect = CombinationEffect(
                    effectRegistry.defaultEffectFunctions(preferences.lookupFunction))
            videoReader.forcePortrait = isPortraitOrientation()
            binding.controlBar.visibility = View.GONE
        }
        else {
            videoReader.effect = originalEffect!!
            videoReader.forcePortrait = null
            binding.controlBar.visibility = View.VISIBLE
        }
        if (!isPlaying) {
            loadFrame(frameIndex)
        }
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
            binding.overlayView.processedBitmap = pb
            binding.overlayView.invalidate()
            updateControls()
        }
    }

    private fun handleOverlayViewTouch(view: OverlayView, event: MotionEvent) {
        // Mostly duplicated from MainActivity.
        if (event.action == MotionEvent.ACTION_UP) {
            if (inEffectSelectionMode) {
                val numEffects = effectRegistry.defaultEffectCount()
                val gridSize = Math.ceil(Math.sqrt(numEffects.toDouble())).toInt()
                val tileWidth = view.width / gridSize
                val tileHeight = view.height / gridSize
                val tileX = (event.x / tileWidth).toInt()
                val tileY = (event.y / tileHeight).toInt()
                val index = gridSize * tileY + tileX

                val effectIndex = Math.min(Math.max(0, index), numEffects - 1)
                val effect = effectRegistry.defaultEffectAtIndex(
                        effectIndex, preferences.lookupFunction)
                originalEffect = effect
                videoReader.effect = effect
                videoReader.forcePortrait = null

                // Update thumbnail and metadata with effect.
                val newMetadata = photoLibrary.metadataForItemId(videoId)
                        .withEffectMetadata(effect.effectMetadata())
                val firstFrame = videoReader.bitmapForFrame(0)
                photoLibrary.writeMetadata(newMetadata, videoId)
                photoLibrary.writeThumbnail(firstFrame, videoId)

                if (!isPlaying) {
                    loadFrame(frameIndex)
                }
                updateInEffectSelectionModeFlag(false)
                binding.controlBar.visibility = View.VISIBLE
            }
        }
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