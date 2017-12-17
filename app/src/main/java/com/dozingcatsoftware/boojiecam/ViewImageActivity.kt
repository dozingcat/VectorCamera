package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.renderscript.RenderScript
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry

class ViewImageActivity : Activity() {
    private val photoLibrary = PhotoLibrary.defaultLibrary()
    private lateinit var rs : RenderScript
    private lateinit var imageId: String
    private lateinit var overlayView: OverlayView

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_image)
        rs = RenderScript.create(this)

        imageId = intent.getStringExtra("imageId")
        overlayView = findViewById(R.id.overlayView)
        loadImage()
    }

    private fun loadImage() {
        val metadata = photoLibrary.metadataForItemId(imageId)
        val width = metadata["width"] as Int
        val height = metadata["height"] as Int
        val planarYuv = photoLibrary.rawFileInputStreamForItemId(imageId).use {
            PlanarYuvAllocations.fromInputStream(rs, it, width, height)
        }
        val xFlipped = (metadata["xFlipped"] == true)
        val orientation = if (xFlipped) ImageOrientation.ROTATED_180 else ImageOrientation.NORMAL
        val inputImage = CameraImage(null, planarYuv,
                orientation, CameraStatus.CAPTURING_PHOTO, 0)

        // Temporary workaround for pictures that didn't save effect dict.
        val effectDict = if (metadata.containsKey("effect"))
            metadata["effect"] as Map<String, Any>
            else mapOf("name" to "edge_luminance", "params" to mapOf<String, Any>())

        val effect = EffectRegistry.forNameAndParameters(rs,
                effectDict["name"] as String,
                effectDict["params"] as Map<String, Any>)
        val bitmap = effect.createBitmap(inputImage)
        val paintFn = effect.createPaintFn(inputImage)
        overlayView.processedBitmap = ProcessedBitmap(effect, inputImage, bitmap, paintFn)
        overlayView.invalidate()
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