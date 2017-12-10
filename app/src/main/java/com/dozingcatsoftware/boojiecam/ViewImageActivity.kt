package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.renderscript.RenderScript

class ViewImageActivity : Activity() {
    private val photoLibrary = PhotoLibrary.defaultLibrary()
    private lateinit var rs : RenderScript
    private lateinit var imageProcessor: CameraAllocationProcessor
    private lateinit var imageId: String
    private lateinit var overlayView: OverlayView

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_image)
        rs = RenderScript.create(this)

        imageProcessor = EdgeColorAllocationProcessor(rs)
        //imageProcessor = SolidColorAllocationProcessor.withFixedColors(
        //        rs, 0x000000, 0xffffff)

        imageId = intent.getStringExtra("imageId")
        overlayView = findViewById(R.id.overlayView)
        loadImage()
    }

    private fun loadImage() {
        val metadata = photoLibrary.metadataForItemId(imageId)
        val width = metadata.get("width") as Int
        val height = metadata.get("height") as Int
        val planarYuv = photoLibrary.rawFileInputStreamForItemId(imageId).use {
            PlanarYuvAllocations.fromInputStream(rs, it, width, height)
        }
        val xFlipped = (metadata.get("xFlipped") == true)
        val orientation = if (xFlipped) ImageOrientation.ROTATED_180 else ImageOrientation.NORMAL
        val inputImage = CameraImage(null, planarYuv,
                orientation, CameraStatus.CAPTURING_PHOTO, 0)

        val bitmap = imageProcessor.createBitmap(inputImage)

//        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//        val yBytes = ByteArray(width * height)
//        val pixels = IntArray(width * height)
//        planarYuv.y.copyTo(yBytes)
//        for (i in 0 until yBytes.size) {
//            val bi = 0xff and yBytes[i].toInt()
//            pixels[i] = (0xff shl 24) or (bi shl 16) or (bi shl 8) or bi
//        }
//        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val paintFn = imageProcessor.createPaintFn(inputImage)
        overlayView.processedBitmap = ProcessedBitmap(inputImage, bitmap, paintFn)
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