package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.renderscript.RenderScript
import android.view.MotionEvent
import android.view.View
import com.dozingcatsoftware.boojiecam.effect.CombinationEffect
import com.dozingcatsoftware.boojiecam.effect.Effect
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry
import com.dozingcatsoftware.util.AndroidUtils
import kotlinx.android.synthetic.main.view_image.*

class ViewImageActivity : Activity() {
    private val photoLibrary = PhotoLibrary.defaultLibrary()
    private lateinit var rs : RenderScript
    private lateinit var imageId: String
    private var inEffectSelectionMode = false
    private val allEffectFactories = EffectRegistry.defaultEffectFactories()
    private val handler = Handler()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_image)
        rs = RenderScript.create(this)

        switchEffectButton.setOnClickListener(this::switchEffect)
        shareButton.setOnClickListener(this::shareImage)
        overlayView.touchEventHandler = this::handleOverlayViewTouch

        imageId = intent.getStringExtra("imageId")
        loadImage()

        // TODO: Controls to change effect, share, delete.
    }

    private fun loadImage() {
        val metadata = photoLibrary.metadataForItemId(imageId)
        // Temporary workaround for pictures that didn't save effect dict.
        val effectDict = if (metadata.containsKey("effect"))
            metadata["effect"] as Map<String, Any>
            else mapOf("name" to "edge_luminance", "params" to mapOf<String, Any>())

        val effect = EffectRegistry.forNameAndParameters(rs,
                effectDict["name"] as String,
                effectDict["params"] as Map<String, Any>)
        showImage(effect, metadata)
    }

    private fun switchEffect(view: View) {
        inEffectSelectionMode = !inEffectSelectionMode
        if (inEffectSelectionMode) {
            val metadata = photoLibrary.metadataForItemId(imageId)
            val comboEffect = CombinationEffect(rs, allEffectFactories)
            // FIXME: This is slow because the saved image is high resolution.
            showImage(comboEffect, photoLibrary.metadataForItemId(imageId))
        }
        else {
            loadImage()
        }
    }

    private fun showImage(effect: Effect, metadata: Map<String, Any>) {
        val width = metadata["width"] as Int
        val height = metadata["height"] as Int
        val planarYuv = photoLibrary.rawFileInputStreamForItemId(imageId).use {
            PlanarYuvAllocations.fromInputStream(rs, it, width, height)
        }
        val xFlipped = (metadata["xFlipped"] == true)
        val orientation = if (xFlipped) ImageOrientation.ROTATED_180 else ImageOrientation.NORMAL
        val inputImage = CameraImage(null, planarYuv,
                orientation, CameraStatus.CAPTURING_PHOTO, 0)

        val bitmap = effect.createBitmap(inputImage)
        val paintFn = effect.createPaintFn(inputImage)
        overlayView.processedBitmap = ProcessedBitmap(effect, inputImage, bitmap, paintFn)
        overlayView.invalidate()
    }

    private fun handleOverlayViewTouch(view: OverlayView, event: MotionEvent) {
        // Mostly duplicated from MainActivity.
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (inEffectSelectionMode) {
                val gridSize = Math.floor(Math.sqrt(allEffectFactories.size.toDouble())).toInt()
                val tileWidth = view.width / gridSize
                val tileHeight = view.height / gridSize
                val tileX = (event.x / tileWidth).toInt()
                val tileY = (event.y / tileHeight).toInt()
                val index = gridSize * tileY + tileX

                val effectIndex = Math.min(Math.max(0, index), allEffectFactories.size - 1)
                val effect = allEffectFactories[effectIndex](rs)
                showImage(effect, photoLibrary.metadataForItemId(imageId))
                // TODO: Update stored metadata (always? Or separate "save" action?)
                inEffectSelectionMode = false
            }
        }
    }

    private fun shareImage(view: View) {
        val callback = {path: String, uri: Uri -> handler.post({
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "image/jpeg"
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "BoojieCam Picture")
            shareIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(shareIntent, "Share Picture Using:"))
        }) as Unit}

        AndroidUtils.scanSavedMediaFile(this, photoLibrary.imageFileForItemId(imageId).path,
                callback)
    }

    companion object {
        fun startActivityWithImageId(parent: Activity, imageId: String): Intent {
            val intent = Intent(parent, ViewImageActivity::class.java)
            intent.putExtra("imageId", imageId)
            parent.startActivityForResult(intent, 0)
            return intent
        }
    }
}