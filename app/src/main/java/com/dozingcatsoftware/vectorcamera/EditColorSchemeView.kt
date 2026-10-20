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
 * Panel for editing a CustomColorScheme: swatches for the four gradient corner colors in the
 * panel's corners, and the background color swatch, the edge/solid checkbox, and the Done
 * button in the middle. Swatches open the color picker. Calls `changeCallback` on every change.
 */
class EditColorSchemeView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    lateinit var activity: FragmentActivity
    var changeCallback: ((CustomColorScheme) -> Unit)? = null
    // Called after the Done button hides the view.
    var doneCallback: (() -> Unit)? = null

    private var scheme = CustomColorScheme(ColorMapMode.EDGE, Color.BLACK,
            Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE)
    private val solidCheckbox: CheckBox
    // Each swatch with the scheme color it shows and how to replace that color.
    private val swatches: List<Triple<View, (CustomColorScheme) -> Int, (CustomColorScheme, Int) -> CustomColorScheme>>

    init {
        val contentView = LayoutInflater.from(context).inflate(
                R.layout.customize_colors, this, false)
        addView(contentView)
        solidCheckbox = contentView.findViewById(R.id.solidCheckbox)
        swatches = listOf(
                Triple(contentView.findViewById(R.id.topLeftColor),
                        {cs: CustomColorScheme -> cs.topLeftColor},
                        {cs: CustomColorScheme, c: Int -> cs.copy(topLeftColor = c)}),
                Triple(contentView.findViewById(R.id.topRightColor),
                        {cs: CustomColorScheme -> cs.topRightColor},
                        {cs: CustomColorScheme, c: Int -> cs.copy(topRightColor = c)}),
                Triple(contentView.findViewById(R.id.bottomLeftColor),
                        {cs: CustomColorScheme -> cs.bottomLeftColor},
                        {cs: CustomColorScheme, c: Int -> cs.copy(bottomLeftColor = c)}),
                Triple(contentView.findViewById(R.id.bottomRightColor),
                        {cs: CustomColorScheme -> cs.bottomRightColor},
                        {cs: CustomColorScheme, c: Int -> cs.copy(bottomRightColor = c)}),
                Triple(contentView.findViewById(R.id.backgroundColor),
                        {cs: CustomColorScheme -> cs.backgroundColor},
                        {cs: CustomColorScheme, c: Int -> cs.copy(backgroundColor = c)}),
        )
        for ((swatch, getColor, setColor) in swatches) {
            swatch.setOnClickListener {
                ColorSwatchRow.showColorPicker(activity, getColor(scheme)) {color ->
                    updateScheme(setColor(scheme, color))
                }
            }
        }
        solidCheckbox.setOnClickListener {
            val mode = if (solidCheckbox.isChecked) ColorMapMode.SOLID else ColorMapMode.EDGE
            updateScheme(scheme.copy(mode = mode))
        }
        contentView.findViewById<Button>(R.id.doneButton).setOnClickListener {
            visibility = View.GONE
            doneCallback?.invoke()
        }
        updateControls()
    }

    fun setScheme(s: CustomColorScheme) {
        scheme = s
        updateControls()
    }

    private fun updateScheme(s: CustomColorScheme) {
        scheme = s
        updateControls()
        changeCallback?.invoke(s)
    }

    private fun updateControls() {
        solidCheckbox.isChecked = scheme.mode == ColorMapMode.SOLID
        for ((swatch, getColor, _) in swatches) {
            swatch.background = ColorSwatchRow.swatchDrawable(context, getColor(scheme))
        }
    }
}
