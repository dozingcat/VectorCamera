package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.renderscript.RenderScript
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import com.dozingcatsoftware.boojiecam.effect.CombinationEffect
import com.dozingcatsoftware.boojiecam.effect.Effect
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry
import com.dozingcatsoftware.util.AndroidUtils
import kotlinx.android.synthetic.main.view_video.*
import java.io.File

/**
 * Created by brian on 1/1/18.
 */
class ViewVideoActivity: Activity() {
    private val photoLibrary = PhotoLibrary.defaultLibrary()
    private lateinit var rs : RenderScript
    private lateinit var videoId: String
    private var inEffectSelectionMode = false
    private var originalEffect: Effect? = null
    private val allEffectFactories = EffectRegistry.defaultEffectFactories()
    private lateinit var videoReader: VideoReader
    private val handler = Handler()

    var timeFn = System::currentTimeMillis
    private var frameIndex = 0
    private var isPlaying = false
    private var playbackStartFrame = 0
    private var playbackStartTimestamp = 0L
    private var audioPlayer: AudioPlayer? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_video)
        rs = RenderScript.create(this)

        shareButton.setOnClickListener(this::shareVideo)
        switchEffectButton.setOnClickListener(this::switchEffect)
        playPauseButton.setOnClickListener(this::togglePlay)
        deleteButton.setOnClickListener(this::deleteVideo)
        overlayView.touchEventHandler = this::handleOverlayViewTouch
        // TODO: sharing

        // Yes, this does I/O.
        videoId = intent.getStringExtra("videoId")
        videoReader = VideoReader(rs, photoLibrary, videoId, AndroidUtils.displaySize(this))

        frameSeekBar.max = videoReader.numberOfFrames() - 1
        frameSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
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

        val audioFile = photoLibrary.rawAudioRandomAccessFileForItemId(videoId)
        if (audioFile != null) {
            audioPlayer = AudioPlayer(audioFile)
        }
    }

    public override fun onPause() {
        stopPlaying()
        super.onPause()
    }

    private fun updateControls() {
        frameSeekBar.progress = frameIndex
        playPauseButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause_white_36dp
                else R.drawable.ic_play_arrow_white_36dp)
    }

    private fun loadFrame(index: Int) {
        frameIndex = index
        val bitmap = videoReader.bitmapForFrame(index)
        overlayView.processedBitmap = bitmap
        overlayView.invalidate()
    }

    private fun switchEffect(view: View) {
        inEffectSelectionMode = !inEffectSelectionMode
        if (inEffectSelectionMode) {
            originalEffect = videoReader.effect
            videoReader.effect = CombinationEffect(rs, allEffectFactories)
        }
        else {
            videoReader.effect = originalEffect!!
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
            overlayView.processedBitmap = pb
            overlayView.invalidate()
            updateControls()
        }
    }

    private fun handleOverlayViewTouch(view: OverlayView, event: MotionEvent) {
        // Mostly duplicated from MainActivity.
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (inEffectSelectionMode) {
                val gridSize = Math.ceil(Math.sqrt(allEffectFactories.size.toDouble())).toInt()
                val tileWidth = view.width / gridSize
                val tileHeight = view.height / gridSize
                val tileX = (event.x / tileWidth).toInt()
                val tileY = (event.y / tileHeight).toInt()
                val index = gridSize * tileY + tileX

                val effectIndex = Math.min(Math.max(0, index), allEffectFactories.size - 1)
                val effect = allEffectFactories[effectIndex](rs)
                originalEffect = effect
                videoReader.effect = effect

                // Update thumbnail and metadata with effect.
                val newMetadata = photoLibrary.metadataForItemId(videoId)
                        .withEffectMetadata(effect.effectMetadata())
                val firstFrame = videoReader.bitmapForFrame(0)
                photoLibrary.writeMetadata(newMetadata, videoId)
                photoLibrary.writeThumbnail(firstFrame, videoId)

                if (!isPlaying) {
                    loadFrame(frameIndex)
                }
                inEffectSelectionMode = false
            }
        }
    }

    private fun encodeVideo() {
        Log.i(TAG, "Starting video export")
        val tempVideoOnlyFile = photoLibrary.tempVideoFileForItemIdWithSuffix(videoId, "noaudio")
        tempVideoOnlyFile.delete()
        tempVideoOnlyFile.parentFile.mkdirs()
        Log.i(TAG, "Writing to ${tempVideoOnlyFile.path}")

        val encoder = WebMEncoder(videoReader, tempVideoOnlyFile.path)
        encoder.startEncoding()
        var frameIndex = 0
        while (frameIndex < videoReader.numberOfFrames()) {
            encoder.encodeFrame(frameIndex)
            Log.i(TAG, "Encoded frame ${frameIndex}")
            frameIndex += 1
        }
        encoder.finishEncoding()
        Log.i(TAG, "Finished encoding")

        var fileToMove: File

        val audioFile = photoLibrary.rawAudioFileForItemId(videoId)
        if (audioFile.exists()) {
            val tempCombinedFile =
                    photoLibrary.tempVideoFileForItemIdWithSuffix(videoId, "combined")
            tempCombinedFile.delete()
            Log.i(TAG, "Adding audio, saving to ${tempCombinedFile.path}")

            CombineAudioVideo.insertAudioIntoWebm(
                    audioFile.path, tempVideoOnlyFile.path, tempCombinedFile.path,
                    {bytesRead -> Log.i(TAG, "Read ${bytesRead} audio bytes")})

            tempVideoOnlyFile.delete()
            fileToMove = tempCombinedFile
        }
        else {
            fileToMove = tempVideoOnlyFile
        }
        val finalOutputFile = photoLibrary.videoFileForItemId(videoId)
        finalOutputFile.parentFile.mkdirs()
        fileToMove.renameTo(finalOutputFile)
        Log.i(TAG, "Wrote to ${finalOutputFile.path}")
    }

    private fun shareVideo(view: View) {
        // TODO: Export if needed (file doesn't exist, or exported metadata doesn't match)
        // Also options for zip archive and text/html for ascii effects.
        encodeVideo()
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
        val TAG = "ViewVideoActivity"

        fun startActivityWithVideoId(parent: Activity, videoId: String): Intent {
            val intent = Intent(parent, ViewVideoActivity::class.java)
            intent.putExtra("videoId", videoId)
            parent.startActivityForResult(intent, 0)
            return intent
        }
    }
}