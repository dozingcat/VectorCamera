package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView.OnItemClickListener
import android.widget.GridView
import android.widget.ImageView
import android.widget.SimpleAdapter
import android.widget.TextView
import com.dozingcatsoftware.util.AndroidUtils
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import org.jetbrains.anko.coroutines.experimental.bg
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*


class ImageListActivity : Activity() {
    private val photoLibrary = PhotoLibrary.defaultLibrary()

    private lateinit var gridView: GridView
    private var gridImageIds: List<String>? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.imagegrid)
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

    private fun loadGridCellImage(view: ImageView, itemId: String) {
        val self = this
        async(UI) {
            val imageUri = Uri.fromFile(self.photoLibrary.thumbnailFileForItemId(itemId))
            val bitmap = (bg {
                AndroidUtils.scaledBitmapFromURIWithMinimumSize(
                        self, imageUri, ImageListActivity.CELL_WIDTH, ImageListActivity.CELL_HEIGHT)
            }).await()
            view.setImageBitmap(bitmap)
        }
    }

    private fun loadGridCellDateField(view: TextView, itemId: String) {
        val self = this
        async(UI) {
            val metadata = (bg {
                self.photoLibrary.metadataForItemId(itemId)
            }).await()
            val dateCreated = Date(metadata.timestamp)
            view.text = ImageListActivity.GRID_DATE_FORMAT.format(dateCreated)
        }
    }

    private fun loadGridCellSizeField(view: TextView, itemId: String) {
        val self = this
        async(UI) {
            val sizeInBytes = (bg {
                self.photoLibrary.fileSizeForItemId(itemId)
            }).await()
            val mb = sizeInBytes / 1e6
            val formatter =
                    if (mb >= 10) ImageListActivity.GRID_SIZE_FORMAT_LARGE
                    else ImageListActivity.GRID_SIZE_FORMAT_SMALL
            view.text = formatter.format(mb) + " MB"
        }
    }

    companion object {
        // These should match the dimensions in imagegrid.xml and imagegrid_cell.xml.
        val CELL_WIDTH = 160
        val CELL_HEIGHT = 120
        val GRID_DATE_FORMAT = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val GRID_SIZE_FORMAT_LARGE = NumberFormat.getIntegerInstance()
        val GRID_SIZE_FORMAT_SMALL = DecimalFormat("0.0")

        fun startIntent(parent: Context) {
            parent.startActivity(Intent(parent, ImageListActivity::class.java))
        }
    }

}

