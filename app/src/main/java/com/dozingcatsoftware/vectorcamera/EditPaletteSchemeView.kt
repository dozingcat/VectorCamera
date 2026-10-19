package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.dozingcatsoftware.vectorcamera.effect.ColorMapMode

/**
 * Panel for editing a CustomPaletteScheme: a row of color swatches that open the color picker,
 * buttons to add and remove colors, and a checkbox to switch between edge and solid mode.
 * Calls `changeCallback` on every change.
 */
class EditPaletteSchemeView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    lateinit var activity: FragmentActivity
    var changeCallback: ((CustomPaletteScheme) -> Unit)? = null

    private var scheme = CustomPaletteScheme(ColorMapMode.SOLID, listOf(Color.BLACK, Color.WHITE))
    private val swatchRow: ColorSwatchRow
    private val addButton: Button
    private val removeButton: Button
    private val solidCheckbox: CheckBox

    init {
        val contentView = LayoutInflater.from(context).inflate(
                R.layout.customize_palette, this, false)
        addView(contentView)
        swatchRow = contentView.findViewById(R.id.colorSwatches)
        addButton = contentView.findViewById(R.id.addColorButton)
        removeButton = contentView.findViewById(R.id.removeColorButton)
        solidCheckbox = contentView.findViewById(R.id.solidCheckbox)

        swatchRow.onColorClicked = this::pickColor
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
        updateControls()
    }

    fun setScheme(s: CustomPaletteScheme) {
        scheme = s
        solidCheckbox.isChecked = s.mode == ColorMapMode.SOLID
        updateControls()
    }

    private fun updateScheme(s: CustomPaletteScheme) {
        scheme = s
        updateControls()
        changeCallback?.invoke(s)
    }

    private fun updateControls() {
        swatchRow.setColors(scheme.colors)
        addButton.isEnabled = scheme.colors.size < CustomPaletteScheme.MAX_COLORS
        removeButton.isEnabled = scheme.colors.size > CustomPaletteScheme.MIN_COLORS
    }

    private fun pickColor(index: Int) {
        ColorSwatchRow.showColorPicker(activity, scheme.colors[index]) {color ->
            val colors = scheme.colors.toMutableList()
            colors[index] = color
            updateScheme(scheme.copy(colors = colors))
        }
    }
}
