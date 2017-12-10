package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView.OnItemClickListener
import android.widget.GridView
import android.widget.ImageView
import android.widget.SimpleAdapter
import com.dozingcatsoftware.util.AndroidUtils
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import org.jetbrains.anko.coroutines.experimental.bg


class ImageListActivity : Activity() {
    private val photoLibrary = PhotoLibrary.defaultLibrary()

    private lateinit var gridView: GridView
    private var gridImageIds: List<String>? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.imagegrid)
        gridView = findViewById(R.id.gridview)
        gridView.onItemClickListener = OnItemClickListener { parent, view, position, id ->
            ViewImageActivity.startActivityWithImageId(
                    this@ImageListActivity, gridImageIds!![position])
        }
    }

    public override fun onResume() {
        super.onResume()
        displayGrid()
    }

    private fun displayGrid() {
        gridImageIds = photoLibrary.allItemIds().asReversed()
        val cellMaps = gridImageIds!!.map(
                { mapOf("thumbnailUri" to Uri.fromFile(photoLibrary.thumbnailFileForItemId(it)))})
        val adapter = SimpleAdapter(this, cellMaps,
                R.layout.imagegrid_cell,
                arrayOf("thumbnailUri"),
                intArrayOf(R.id.grid_image))
        adapter.viewBinder = SimpleAdapter.ViewBinder { view, data, _ ->
            val imageUri = data as Uri
            loadImageIntoViewAsync(imageUri, view as ImageView, CELL_WIDTH, CELL_HEIGHT, this.resources)
            true
        }
        gridView.adapter = adapter

        // show text message if no images available
        val noImagesView = findViewById<View>(R.id.noImagesTextView)
        noImagesView.visibility = if (cellMaps.isNotEmpty()) View.GONE else View.VISIBLE

        System.gc() // seems to avoid OutOfMemoryErrors when selecting image after deleting earlier image
    }

    private fun loadImageIntoViewAsync(
            uri: Uri, view: ImageView, width: Int, height: Int, resources: Resources) {
        val self = this
        async(UI) {
            val bitmap = (bg {
                AndroidUtils.scaledBitmapFromURIWithMinimumSize(self, uri, width, height)
            }).await()
            view.setImageBitmap(bitmap)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        /*
        if (resultCode == ViewImageActivity.DELETE_RESULT) {
            imageDirectories.removeAt(selectedGridIndex)
            imageDirectoryMaps.removeAt(selectedGridIndex)
            displayGrid()
        }
        */
    }

    companion object {
        // These should match the dimensions in imagegrid.xml and imagegrid_cell.xml.
        internal var CELL_WIDTH = 160
        internal var CELL_HEIGHT = 120
    }

}

