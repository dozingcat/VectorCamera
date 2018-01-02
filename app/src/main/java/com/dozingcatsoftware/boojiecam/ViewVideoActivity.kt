package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.renderscript.RenderScript
import android.widget.SeekBar
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry
import kotlinx.android.synthetic.main.view_video.*

/**
 * Created by brian on 1/1/18.
 */
class ViewVideoActivity: Activity() {
    private val photoLibrary = PhotoLibrary.defaultLibrary()
    private lateinit var rs : RenderScript
    private lateinit var videoId: String
    private var inEffectSelectionMode = false
    private val allEffectFactories = EffectRegistry.defaultEffectFactories()
    private lateinit var videoReader: VideoReader
    private var frameIndex = 0
    private val handler = Handler()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_video)
        rs = RenderScript.create(this)

        // Yes, this does I/O.
        videoId = intent.getStringExtra("videoId")
        videoReader = VideoReader(rs, photoLibrary, videoId)

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

    companion object {
        fun startActivityWithVideoId(parent: Activity, videoId: String): Intent {
            val intent = Intent(parent, ViewVideoActivity::class.java)
            intent.putExtra("videoId", videoId)
            parent.startActivityForResult(intent, 0)
            return intent
        }
    }
}