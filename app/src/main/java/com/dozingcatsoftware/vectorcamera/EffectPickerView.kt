package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.AttributeSet
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dozingcatsoftware.vectorcamera.databinding.EffectPickerCellBinding
import com.dozingcatsoftware.vectorcamera.databinding.EffectPickerHeaderBinding
import com.dozingcatsoftware.vectorcamera.effect.EffectCategory
import com.dozingcatsoftware.vectorcamera.effect.EffectInfo

/**
 * Scrollable grid of effect thumbnails from which the user picks an effect. The view owns the
 * layout and the thumbnail bitmaps; it doesn't render thumbnails itself. Whoever shows the view
 * renders thumbnails (see EffectThumbnails.kt) and passes them to `setThumbnail`. The renderer
 * can read `visibleEffectIds` and `thumbnailSize` from any thread to find out what to render.
 */
class EffectPickerView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    private sealed class Item {
        data class Header(val category: EffectCategory) : Item()
        data class Cell(val info: EffectInfo) : Item()
    }

    private val recyclerView = RecyclerView(context)
    private val layoutManager = GridLayoutManager(context, PORTRAIT_COLUMNS)
    private val adapter = PickerAdapter()
    private val thumbnails = HashMap<String, Bitmap>()
    private var effects: List<EffectInfo> = listOf()
    private var items: List<Item> = listOf()

    // Shape of the images being rendered, used to give the cells the same aspect ratio.
    private var sourceLandscapeWidth = 16
    private var sourceLandscapeHeight = 9
    private var sourceIsPortrait = false

    private var cellWidth = 0
    private var cellHeight = 0

    /** Called on the main thread when the user taps an effect. */
    var onEffectSelected: ((EffectInfo) -> Unit)? = null

    /**
     * Called on the main thread when a cell is shown that has no thumbnail yet. Renderers that
     * produce thumbnails on demand use this; the live camera renderer ignores it and polls
     * `visibleEffectIds` instead.
     */
    var onThumbnailNeeded: ((EffectInfo) -> Unit)? = null

    /** IDs of the effects currently on screen, in display order. Safe to read from any thread. */
    @Volatile var visibleEffectIds: List<String> = listOf()
        private set

    /**
     * Landscape size in pixels at which thumbnails should be rendered, based on the current
     * cell size. Safe to read from any thread.
     */
    @Volatile var thumbnailSize = Size(MIN_THUMBNAIL_WIDTH, MIN_THUMBNAIL_HEIGHT)
        private set

    var showCategoryHeaders = false
        set(value) {
            if (field != value) {
                field = value
                rebuildItems()
            }
        }

    var selectedEffectId: String? = null
        set(value) {
            if (field != value) {
                field = value
                adapter.notifyDataSetChanged()
            }
        }

    init {
        setBackgroundColor(Color.BLACK)
        // Consume touches so they don't reach views underneath.
        isClickable = true
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (items[position] is Item.Header) layoutManager.spanCount else 1
            }
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateVisibleEffectIds()
            }
        })
        // Layout change listeners run after the RecyclerView has positioned its children.
        recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateVisibleEffectIds()
        }
        // Child attach/detach events fire in the middle of a layout pass, before the newly added
        // views have been positioned, so a view attached last (e.g. the bottom row) would not
        // yet count as visible. Defer the update until the layout pass is done.
        recyclerView.addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) = scheduleVisibleIdsUpdate()
                    override fun onChildViewDetachedFromWindow(view: View) = scheduleVisibleIdsUpdate()
                })
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setEffects(infos: List<EffectInfo>) {
        effects = infos
        rebuildItems()
    }

    /**
     * Sets the shape of the source images so cells can match their aspect ratio. Width and
     * height are the landscape dimensions; `portrait` indicates the image is rotated for display.
     * Clears existing thumbnails if the shape changed.
     */
    fun setSourceShape(landscapeWidth: Int, landscapeHeight: Int, portrait: Boolean) {
        if (landscapeWidth == sourceLandscapeWidth && landscapeHeight == sourceLandscapeHeight &&
                portrait == sourceIsPortrait) {
            return
        }
        sourceLandscapeWidth = landscapeWidth
        sourceLandscapeHeight = landscapeHeight
        sourceIsPortrait = portrait
        clearThumbnails()
        updateCellSize()
    }

    /** Stores a rendered thumbnail and shows it if the effect's cell is on screen. */
    fun setThumbnail(effectId: String, bitmap: Bitmap) {
        thumbnails[effectId] = bitmap
        val position = items.indexOfFirst { it is Item.Cell && it.info.id == effectId }
        if (position >= 0) {
            val holder = recyclerView.findViewHolderForAdapterPosition(position)
            if (holder is CellViewHolder) {
                holder.binding.thumbnailView.setImageBitmap(bitmap)
            }
        }
    }

    fun clearThumbnails() {
        thumbnails.clear()
        adapter.notifyDataSetChanged()
    }

    fun scrollToEffect(effectId: String?) {
        val position = items.indexOfFirst { it is Item.Cell && it.info.id == effectId }
        if (position >= 0) {
            layoutManager.scrollToPositionWithOffset(position, 0)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutManager.spanCount = if (w > h) LANDSCAPE_COLUMNS else PORTRAIT_COLUMNS
        updateCellSize()
    }

    private fun rebuildItems() {
        val newItems = mutableListOf<Item>()
        var lastCategory: EffectCategory? = null
        for (info in effects) {
            if (showCategoryHeaders && info.category != lastCategory) {
                newItems.add(Item.Header(info.category))
                lastCategory = info.category
            }
            newItems.add(Item.Cell(info))
        }
        items = newItems
        adapter.notifyDataSetChanged()
    }

    private fun updateCellSize() {
        if (width == 0) {
            return
        }
        cellWidth = width / layoutManager.spanCount
        val aspect =
                if (sourceIsPortrait) sourceLandscapeHeight.toDouble() / sourceLandscapeWidth
                else sourceLandscapeWidth.toDouble() / sourceLandscapeHeight
        cellHeight = (cellWidth / aspect).toInt()
        // Render thumbnails at half the cell resolution; they're small enough that the
        // difference isn't visible and it halves the per-frame processing cost.
        val longSide = Math.max(cellWidth, cellHeight) * THUMBNAIL_SCALE
        val shortSide = Math.min(cellWidth, cellHeight) * THUMBNAIL_SCALE
        thumbnailSize = Size(
                Math.max(MIN_THUMBNAIL_WIDTH, roundToEven(longSide)),
                Math.max(MIN_THUMBNAIL_HEIGHT, roundToEven(shortSide)))
        // This can be called during a layout pass (from onSizeChanged), when the RecyclerView
        // won't accept adapter changes, so defer the update.
        post { adapter.notifyDataSetChanged() }
    }

    private var visibleIdsUpdatePending = false

    private fun scheduleVisibleIdsUpdate() {
        if (!visibleIdsUpdatePending) {
            visibleIdsUpdatePending = true
            post {
                visibleIdsUpdatePending = false
                updateVisibleEffectIds()
            }
        }
    }

    private fun updateVisibleEffectIds() {
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first < 0 || last < 0) {
            visibleEffectIds = listOf()
            return
        }
        val ids = mutableListOf<String>()
        for (i in first..last) {
            val item = items[i]
            if (item is Item.Cell) {
                ids.add(item.info.id)
            }
        }
        visibleEffectIds = ids
    }

    private class CellViewHolder(val binding: EffectPickerCellBinding) :
            RecyclerView.ViewHolder(binding.root) {
        var effectId: String? = null
    }

    private class HeaderViewHolder(val binding: EffectPickerHeaderBinding) :
            RecyclerView.ViewHolder(binding.root)

    private inner class PickerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) =
                if (items[position] is Item.Header) VIEW_TYPE_HEADER else VIEW_TYPE_CELL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_HEADER) {
                HeaderViewHolder(EffectPickerHeaderBinding.inflate(inflater, parent, false))
            }
            else {
                CellViewHolder(EffectPickerCellBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> {
                    (holder as HeaderViewHolder).binding.headerView.text = item.category.displayName
                }
                is Item.Cell -> {
                    val info = item.info
                    holder as CellViewHolder
                    holder.effectId = info.id
                    val binding = holder.binding
                    binding.root.layoutParams.height = cellHeight
                    binding.nameView.text = info.name
                    binding.selectedOutline.visibility =
                            if (info.id == selectedEffectId) View.VISIBLE else View.GONE
                    val bitmap = thumbnails[info.id]
                    binding.thumbnailView.setImageBitmap(bitmap)
                    if (bitmap == null) {
                        onThumbnailNeeded?.invoke(info)
                    }
                    binding.root.setOnClickListener { onEffectSelected?.invoke(info) }
                }
            }
        }

        // RecyclerView may bind a view before it's attached (prefetching while scrolling), and
        // a thumbnail rendered in between won't have been set because `setThumbnail` only
        // updates attached views. So check for a cached thumbnail again on attach.
        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            if (holder is CellViewHolder) {
                val id = holder.effectId ?: return
                val bitmap = thumbnails[id]
                if (bitmap != null) {
                    holder.binding.thumbnailView.setImageBitmap(bitmap)
                }
                else {
                    effects.find { it.id == id }?.let { onThumbnailNeeded?.invoke(it) }
                }
            }
        }
    }

    companion object {
        const val PORTRAIT_COLUMNS = 3
        const val LANDSCAPE_COLUMNS = 4
        const val THUMBNAIL_SCALE = 0.5
        const val MIN_THUMBNAIL_WIDTH = 64
        const val MIN_THUMBNAIL_HEIGHT = 36
        private const val VIEW_TYPE_CELL = 0
        private const val VIEW_TYPE_HEADER = 1

        private fun roundToEven(x: Double) = (Math.round(x / 2) * 2).toInt()
    }
}
