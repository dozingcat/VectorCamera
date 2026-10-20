package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity

/**
 * Panel for editing a CustomContourScheme: a row of fill color swatches with buttons to add and
 * remove colors, and a swatch for the contour line color. Each swatch opens the color picker.
 * Calls `changeCallback` on every change.
 */
class EditContourSchemeView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    lateinit var activity: FragmentActivity
    var changeCallback: ((CustomContourScheme) -> Unit)? = null
    // Called after the Done button hides the view.
    var doneCallback: (() -> Unit)? = null

    private var scheme = CustomContourScheme(listOf(Color.BLACK, Color.WHITE), Color.BLACK)
    private val fillSwatches: ColorSwatchRow
    private val lineSwatch: View
    private val addButton: Button
    private val removeButton: Button

    init {
        val contentView = LayoutInflater.from(context).inflate(
                R.layout.customize_contour, this, false)
        addView(contentView)
        fillSwatches = contentView.findViewById(R.id.fillSwatches)
        lineSwatch = contentView.findViewById(R.id.lineSwatch)
        addButton = contentView.findViewById(R.id.addColorButton)
        removeButton = contentView.findViewById(R.id.removeColorButton)

        fillSwatches.onColorClicked = this::pickFillColor
        lineSwatch.setOnClickListener {
            ColorSwatchRow.showColorPicker(activity, scheme.lineColor) {color ->
                updateScheme(scheme.copy(lineColor = color))
            }
        }
        addButton.setOnClickListener {
            if (scheme.fillColors.size < CustomContourScheme.MAX_COLORS) {
                // New colors start as a copy of the last one, so adding doesn't change the look
                // until the user picks a color.
                updateScheme(scheme.copy(fillColors = scheme.fillColors + scheme.fillColors.last()))
            }
        }
        removeButton.setOnClickListener {
            if (scheme.fillColors.size > CustomContourScheme.MIN_COLORS) {
                updateScheme(scheme.copy(fillColors = scheme.fillColors.dropLast(1)))
            }
        }
        contentView.findViewById<Button>(R.id.doneButton).setOnClickListener {
            visibility = View.GONE
            doneCallback?.invoke()
        }
        updateControls()
    }

    fun setScheme(s: CustomContourScheme) {
        scheme = s
        updateControls()
    }

    private fun updateScheme(s: CustomContourScheme) {
        scheme = s
        updateControls()
        changeCallback?.invoke(s)
    }

    private fun updateControls() {
        fillSwatches.setColors(scheme.fillColors)
        lineSwatch.background = ColorSwatchRow.swatchDrawable(context, scheme.lineColor)
        addButton.isEnabled = scheme.fillColors.size < CustomContourScheme.MAX_COLORS
        removeButton.isEnabled = scheme.fillColors.size > CustomContourScheme.MIN_COLORS
    }

    private fun pickFillColor(index: Int) {
        ColorSwatchRow.showColorPicker(activity, scheme.fillColors[index]) {color ->
            val colors = scheme.fillColors.toMutableList()
            colors[index] = color
            updateScheme(scheme.copy(fillColors = colors))
        }
    }
}
