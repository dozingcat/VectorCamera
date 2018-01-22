package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Intent
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

        // TODO: Controls to delete, (maybe) save .
    }

    private fun loadImage() {
        val metadata = photoLibrary.metadataForItemId(imageId)
        val effect = EffectRegistry.forMetadata(rs, metadata.effectMetadata)
        showImage(effect, metadata)
    }

    private fun switchEffect(view: View) {
        inEffectSelectionMode = !inEffectSelectionMode
        if (inEffectSelectionMode) {
            val comboEffect = CombinationEffect(rs, allEffectFactories)
            // FIXME: This is slow because the saved image is high resolution.
            showImage(comboEffect, photoLibrary.metadataForItemId(imageId))
        }
        else {
            loadImage()
        }
    }

    private fun createProcessedBitmap(effect: Effect, metadata: MediaMetadata): ProcessedBitmap {
        val planarYuv = photoLibrary.rawImageFileInputStreamForItemId(imageId).use {
            PlanarYuvAllocations.fromInputStream(rs, it, metadata.width, metadata.height)
        }
        val inputImage = CameraImage(null, planarYuv, metadata.orientation,
                CameraStatus.CAPTURING_PHOTO, 0, AndroidUtils.displaySize(this))

        val bitmap = effect.createBitmap(inputImage)
        val paintFn = effect.createPaintFn(inputImage)
        return ProcessedBitmap(effect, inputImage, bitmap, paintFn)
    }

    private fun showImage(effect: Effect, metadata: MediaMetadata) {
        overlayView.processedBitmap = createProcessedBitmap(effect, metadata)
        overlayView.invalidate()
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
                // Update metadata and thumbnail and full size images.
                val newMetadata = photoLibrary.metadataForItemId(imageId)
                        .withEffectMetadata(effect.effectMetadata())
                val pb = createProcessedBitmap(effect, newMetadata)
                photoLibrary.writeMetadata(newMetadata, imageId)
                photoLibrary.writeImageAndThumbnail(this, pb, imageId)
                overlayView.processedBitmap = pb
                overlayView.invalidate()
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