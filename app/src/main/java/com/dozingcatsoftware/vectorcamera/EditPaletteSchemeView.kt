package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import com.dozingcatsoftware.vectorcamera.effect.ColorMapMode
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener

/**
 * Panel for editing a CustomPaletteScheme: a row of color swatches that open the color picker,
 * buttons to add and remove colors, and a checkbox to switch between edge and solid mode.
 * Calls `changeCallback` on every change.
 */
class EditPaletteSchemeView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    lateinit var activity: FragmentActivity
    var changeCallback: ((CustomPaletteScheme) -> Unit)? = null

    private var scheme = CustomPaletteScheme(ColorMapMode.SOLID, listOf(Color.BLACK, Color.WHITE))
    private val swatchContainer: LinearLayout
    private val addButton: Button
    private val removeButton: Button
    private val solidCheckbox: CheckBox
    private val maxSwatchSize = dpToPx(44f)
    private val swatchMargin = dpToPx(3f)

    init {
        val contentView = LayoutInflater.from(context).inflate(
                R.layout.customize_palette, this, false)
        addView(contentView)
        swatchContainer = contentView.findViewById(R.id.colorSwatches)
        addButton = contentView.findViewById(R.id.addColorButton)
        removeButton = contentView.findViewById(R.id.removeColorButton)
        solidCheckbox = contentView.findViewById(R.id.solidCheckbox)

        addButton.setOnClickListener {
            if (scheme.colors.size < CustomPaletteScheme.MAX_COLORS) {
                // New colors start as a copy of the last one, so adding doesn't change the look
                // until the user picks a color.
                updateScheme(scheme.copy(colors = scheme.colors + scheme.colors.last()))
            }
        }
        removeButton.setOnClickListener {
            if (scheme.colors.size > CustomPaletteScheme.MIN_COLORS) {
                updateScheme(scheme.copy(colors = scheme.colors.dropLast(1)))
            }
        }
        solidCheckbox.setOnClickListener {
            val mode = if (solidCheckbox.isChecked) ColorMapMode.SOLID else ColorMapMode.EDGE
            updateScheme(scheme.copy(mode = mode))
        }
        contentView.findViewById<Button>(R.id.doneButton).setOnClickListener {
            visibility = View.GONE
        }
        rebuildSwatches()
    }

    fun setScheme(s: CustomPaletteScheme) {
        scheme = s
        solidCheckbox.isChecked = s.mode == ColorMapMode.SOLID
        rebuildSwatches()
    }

    private fun updateScheme(s: CustomPaletteScheme) {
        scheme = s
        rebuildSwatches()
        changeCallback?.invoke(s)
    }

    /**
     * Shrinks the swatches so the whole row fits in the available width, which matters for
     * phones in portrait with many colors. Called during measurement, so it only updates the
     * layout params that the pending measure pass will read.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = swatchContainer.childCount
        if (count > 0 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val content = getChildAt(0)
            val available = MeasureSpec.getSize(widthMeasureSpec) -
                    content.paddingLeft - content.paddingRight
            val size = (available / count - 2 * swatchMargin).coerceIn(dpToPx(12f), maxSwatchSize)
            for (i in 0 until count) {
                val params = swatchContainer.getChildAt(i).layoutParams
                if (params.width != size) {
                    params.width = size
                    params.height = size
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun rebuildSwatches() {
        swatchContainer.removeAllViews()
        val size = maxSwatchSize
        val margin = swatchMargin
        for ((index, color) in scheme.colors.withIndex()) {
            val swatch = View(context)
            swatch.background = GradientDrawable().apply {
                setColor(color or 0xFF000000.toInt())
                setStroke(dpToPx(2f), Color.WHITE)
                cornerRadius = dpToPx(4f).toFloat()
            }
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(margin, margin, margin, margin)
            swatch.layoutParams = params
            swatch.setOnClickListener { pickColor(index) }
            swatchContainer.addView(swatch)
        }
        addButton.isEnabled = scheme.colors.size < CustomPaletteScheme.MAX_COLORS
        removeButton.isEnabled = scheme.colors.size > CustomPaletteScheme.MIN_COLORS
    }

    private fun pickColor(index: Int) {
        // Make sure alpha is set.
        val currentColor = scheme.colors[index] or 0xFF000000.toInt()
        val dlg = ColorPickerDialog.newBuilder().setColor(currentColor).create()
        dlg.setColorPickerDialogListener(object : ColorPickerDialogListener {
            override fun onDialogDismissed(dialogId: Int) {}

            override fun onColorSelected(dialogId: Int, color: Int) {
                val colors = scheme.colors.toMutableList()
                colors[index] = color
                updateScheme(scheme.copy(colors = colors))
            }
        })
        dlg.show(activity.supportFragmentManager, "color-picker-dialog")
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }
}
