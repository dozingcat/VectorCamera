package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.renderscript.RenderScript
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import com.dozingcatsoftware.boojiecam.effect.CombinationEffect
import com.dozingcatsoftware.boojiecam.effect.Effect
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry
import com.dozingcatsoftware.util.AndroidUtils
import kotlinx.android.synthetic.main.view_video.*

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
    private var frameIndex = 0
    private var isPlaying = false
    private val handler = Handler()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_video)
        rs = RenderScript.create(this)

        switchEffectButton.setOnClickListener(this::switchEffect)
        playButton.setOnClickListener(this::togglePlay)
        overlayView.touchEventHandler = this::handleOverlayViewTouch
        // TODO: sharing

        // Yes, this does I/O.
        videoId = intent.getStringExtra("videoId")
        videoReader = VideoReader(rs, photoLibrary, videoId, AndroidUtils.displaySize(this))

        frameSeekBar.max = videoReader.numberOfFrames() - 1
        frameSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                loadFrame(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        loadFrame(0)
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
        loadFrame(frameIndex)
    }

    private fun togglePlay(view: View) {
        isPlaying = !isPlaying
        if (isPlaying) {
            scheduleNextFrame()
        }
    }

    private fun scheduleNextFrame() {
        if (isPlaying) {
            handler.postDelayed({showNextFrame()}, delayForNextFrame())
        }
    }

    private fun showNextFrame() {
        if (isPlaying) {
            if (frameIndex < videoReader.numberOfFrames() - 1) {
                frameIndex += 1
                loadFrame(frameIndex)
                frameSeekBar.progress = frameIndex
                scheduleNextFrame()
            }
            else {
                isPlaying = false
            }
        }
    }

    private fun delayForNextFrame(): Long {
        return 25
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
                loadFrame(frameIndex)
                // TODO: Update stored metadata (always? Or separate "save" action?)
                inEffectSelectionMode = false
            }
        }
    }

    companion object {
        fun startActivityWithVideoId(parent: Activity, videoId: String): Intent {
            val intent = Intent(parent, ViewVideoActivity::class.java)
            intent.putExtra("videoId", videoId)
            parent.startActivityForResult(intent, 0)
            return intent
        }
    }
}