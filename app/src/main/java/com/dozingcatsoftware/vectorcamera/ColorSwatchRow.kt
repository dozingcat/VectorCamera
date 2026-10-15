package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener

/**
 * A horizontal row of tappable color swatches, used by the custom effect editors. Swatches are
 * normally `maxSwatchSizeDp` square but shrink evenly so the row always fits its available width,
 * which matters for phones in portrait with many colors.
 */
class ColorSwatchRow(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var onColorClicked: ((Int) -> Unit)? = null
    var maxSwatchSizeDp = 44f

    private var colors: List<Int> = listOf()
    private val margin = dpToPx(3f)

    init {
        orientation = HORIZONTAL
    }

    fun setColors(newColors: List<Int>) {
        colors = newColors
        removeAllViews()
        val size = dpToPx(maxSwatchSizeDp)
        for ((index, color) in colors.withIndex()) {
            val swatch = View(context)
            swatch.background = swatchDrawable(context, color)
            val params = LayoutParams(size, size)
            params.setMargins(margin, margin, margin, margin)
            swatch.layoutParams = params
            swatch.setOnClickListener { onColorClicked?.invoke(index) }
            addView(swatch)
        }
    }

    /**
     * Sizes the swatches to fit the available width. Called during measurement, so it only
     * updates the layout params that the pending measure pass will read.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = childCount
        if (count > 0 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val size = (available / count - 2 * margin).coerceIn(dpToPx(12f), dpToPx(maxSwatchSizeDp))
            for (i in 0 until count) {
                val params = getChildAt(i).layoutParams
                if (params.width != size) {
                    params.width = size
                    params.height = size
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    companion object {
        /** Rounded rectangle filled with `color` and outlined in white. */
        fun swatchDrawable(context: Context, color: Int): GradientDrawable {
            val metrics = context.resources.displayMetrics
            return GradientDrawable().apply {
                setColor(color or 0xFF000000.toInt())
                setStroke(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, metrics).toInt(),
                          Color.WHITE)
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, metrics)
            }
        }

        /** Shows the color picker dialog starting at `color`, calling `onSelected` with the result. */
        fun showColorPicker(activity: FragmentActivity, color: Int, onSelected: (Int) -> Unit) {
            // Make sure alpha is set.
            val dlg = ColorPickerDialog.newBuilder().setColor(color or 0xFF000000.toInt()).create()
            dlg.setColorPickerDialogListener(object : ColorPickerDialogListener {
                override fun onDialogDismissed(dialogId: Int) {}

                override fun onColorSelected(dialogId: Int, color: Int) {
                    onSelected(color)
                }
            })
            dlg.show(activity.supportFragmentManager, "color-picker-dialog")
        }
    }
}
