package com.dozingcatsoftware.vectorcamera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.widget.AdapterView.OnItemClickListener
import android.widget.GridView
import android.widget.ImageView
import android.widget.SimpleAdapter
import android.widget.TextView
import com.dozingcatsoftware.util.scaledBitmapFromURIWithMinimumSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Date


class ImageListActivity : Activity() {
    private lateinit var photoLibrary: PhotoLibrary

    private lateinit var gridView: GridView
    private var gridImageIds: List<String>? = null
    private val handler = Handler()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.imagegrid)
        photoLibrary = PhotoLibrary.defaultLibrary(this)
        val self = this
        gridView = findViewById(R.id.gridview)
        gridView.onItemClickListener = OnItemClickListener { parent, view, position, id ->
            val itemId = gridImageIds!![position]
            val metadata = photoLibrary.metadataForItemId(itemId)
            when (metadata.mediaType) {
                MediaType.IMAGE -> ViewImageActivity.startActivityWithImageId(self, itemId)
                MediaType.VIDEO -> ViewVideoActivity.startActivityWithVideoId(self, itemId)
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        displayGrid()
    }

    private fun displayGrid() {
        gridImageIds = photoLibrary.allItemIds().sortedDescending()
        val cellMaps = gridImageIds!!.map({mapOf("itemId" to it)})
        // Bind to the image view, date field, and time field. The view binder will be called
        // with each of those views and the corresponding map value (always "itemId").
        val adapter = SimpleAdapter(this, cellMaps,
                R.layout.imagegrid_cell,
                arrayOf("itemId", "itemId", "itemId"),
                intArrayOf(R.id.grid_image, R.id.dateField, R.id.sizeField))
        adapter.viewBinder = SimpleAdapter.ViewBinder { view, data, _ ->
            val itemId = (data as String)
            when (view.id) {
                R.id.grid_image -> loadGridCellImage(view as ImageView, itemId)
                R.id.dateField -> loadGridCellDateField(view as TextView, itemId)
                R.id.sizeField -> loadGridCellSizeField(view as TextView, itemId)
            }
            true
        }
        gridView.adapter = adapter

        // show text message if no images available
        val noImagesView = findViewById<View>(R.id.noImagesTextView)
        noImagesView.visibility = if (cellMaps.isNotEmpty()) View.GONE else View.VISIBLE
    }

    // Images and metadata info are loaded asynchronously using Kotlin coroutines:
    private fun loadGridCellImage(view: ImageView, itemId: String) {
        val self = this
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val imageUri = Uri.fromFile(self.photoLibrary.thumbnailFileForItemId(itemId))
                val bitmap = scaledBitmapFromURIWithMinimumSize(
                        self, imageUri, ImageListActivity.CELL_WIDTH, ImageListActivity.CELL_HEIGHT)
                handler.post {view.setImageBitmap(bitmap)}
            }
            catch (ex: Exception) {
                Log.e(TAG, "Error reading image thumbnail", ex)
            }
        }
    }

    private fun loadGridCellDateField(view: TextView, itemId: String) {
        val self = this
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val metadata = self.photoLibrary.metadataForItemId(itemId)
                val dateCreated = Date(metadata.timestamp)
                handler.post {view.text = ImageListActivity.GRID_DATE_FORMAT.format(dateCreated)}
            }
            catch (ex: Exception) {
                Log.e(TAG, "Error reading image date", ex)
            }
        }
    }

    private fun loadGridCellSizeField(view: TextView, itemId: String) {
        val self = this
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val sizeInBytes = self.photoLibrary.fileSizeForItemId(itemId)
                val mb = sizeInBytes / 1e6
                val formatter =
                        if (mb >= 10) ImageListActivity.GRID_SIZE_FORMAT_LARGE
                        else ImageListActivity.GRID_SIZE_FORMAT_SMALL
                handler.post {view.text = formatter.format(mb) + " MB"}
            }
            catch (ex: Exception) {
                Log.e(TAG, "Error reading image size", ex);
            }
        }
    }

    companion object {
        const val TAG = "ImageListActivity"
        // These should match the dimensions in imagegrid.xml and imagegrid_cell.xml.
        const val CELL_WIDTH = 160
        const val CELL_HEIGHT = 120
        val GRID_DATE_FORMAT = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val GRID_SIZE_FORMAT_LARGE = NumberFormat.getIntegerInstance()
        val GRID_SIZE_FORMAT_SMALL = DecimalFormat("0.0")

        fun startIntent(parent: Context) {
            parent.startActivity(Intent(parent, ImageListActivity::class.java))
        }
    }
}

