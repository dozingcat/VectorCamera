package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Spinner
import com.dozingcatsoftware.vectorcamera.effect.ColorComponentSource

/**
 * Panel for editing a CustomPermuteScheme: a dropdown for the source of each output color
 * component, and a checkbox to flip the hue. Calls `changeCallback` on every change.
 */
class EditPermuteSchemeView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    private var scheme = CustomPermuteScheme(
            ColorComponentSource.RED, ColorComponentSource.GREEN, ColorComponentSource.BLUE)
    var changeCallback: ((CustomPermuteScheme) -> Unit)? = null

    private lateinit var redSpinner: Spinner
    private lateinit var greenSpinner: Spinner
    private lateinit var blueSpinner: Spinner
    private lateinit var flipHueCheckbox: CheckBox
    // Set while the controls are being updated from `setScheme`, so that the resulting
    // selection events don't get reported as user changes.
    private var updatingControls = false

    init {
        val contentView = LayoutInflater.from(context).inflate(
                R.layout.customize_permute, this, false)
        addView(contentView)

        redSpinner = contentView.findViewById(R.id.redSourceSpinner)
        greenSpinner = contentView.findViewById(R.id.greenSourceSpinner)
        blueSpinner = contentView.findViewById(R.id.blueSourceSpinner)
        flipHueCheckbox = contentView.findViewById(R.id.flipHueCheckbox)

        setupSpinner(redSpinner) {s, src -> s.copy(redSource = src)}
        setupSpinner(greenSpinner) {s, src -> s.copy(greenSource = src)}
        setupSpinner(blueSpinner) {s, src -> s.copy(blueSource = src)}

        flipHueCheckbox.setOnClickListener {
            updateScheme(scheme.copy(flipUV = flipHueCheckbox.isChecked))
        }
        contentView.findViewById<Button>(R.id.doneButton).setOnClickListener {
            visibility = View.GONE
        }
    }

    fun setScheme(s: CustomPermuteScheme) {
        scheme = s
        updatingControls = true
        redSpinner.setSelection(SOURCES.indexOf(s.redSource))
        greenSpinner.setSelection(SOURCES.indexOf(s.greenSource))
        blueSpinner.setSelection(SOURCES.indexOf(s.blueSource))
        flipHueCheckbox.isChecked = s.flipUV
        updatingControls = false
    }

    private fun setupSpinner(
            spinner: Spinner,
            applyFn: (CustomPermuteScheme, ColorComponentSource) -> CustomPermuteScheme) {
        val adapter = ArrayAdapter(context, R.layout.permute_spinner_item, SOURCE_LABELS)
        adapter.setDropDownViewResource(R.layout.permute_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingControls) {
                    return
                }
                val newScheme = applyFn(scheme, SOURCES[position])
                if (newScheme != scheme) {
                    updateScheme(newScheme)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateScheme(s: CustomPermuteScheme) {
        scheme = s
        changeCallback?.invoke(s)
    }

    companion object {
        // Sources in the order shown in the dropdowns, with user-facing labels.
        val SOURCES = listOf(
                ColorComponentSource.RED,
                ColorComponentSource.GREEN,
                ColorComponentSource.BLUE,
                ColorComponentSource.BRIGHTNESS,
                ColorComponentSource.RED_INVERSE,
                ColorComponentSource.GREEN_INVERSE,
                ColorComponentSource.BLUE_INVERSE,
                ColorComponentSource.BRIGHTNESS_INVERSE,
                ColorComponentSource.MIN,
                ColorComponentSource.MAX,
        )
        val SOURCE_LABELS = listOf(
                "Red",
                "Green",
                "Blue",
                "Brightness",
                "Inverse red",
                "Inverse green",
                "Inverse blue",
                "Inverse brightness",
                "Black",
                "White",
        )
    }
}
