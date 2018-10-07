package com.dozingcatsoftware.vectorcamera

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener

class EditColorSchemeView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    var scheme = CustomColorScheme(CustomColorSchemeType.EDGE, 0, 0, 0, 0, 0)
    var changeCallback: ((CustomColorScheme) -> Unit)? = null
    lateinit var contentView: View
    lateinit var activity: Activity

    init {
        initView()
    }

    private fun initView() {
        val inflater = LayoutInflater.from(context)
        contentView = inflater.inflate(R.layout.customize_colors, null, false)
        val params = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        this.addView(contentView, params)

        contentView.findViewById<Button>(R.id.topLeftColor).setOnClickListener {
            handleColorButton(
                    {cs: CustomColorScheme -> cs.topLeftColor},
                    {cs: CustomColorScheme, color: Int -> cs.copy(topLeftColor=color)})
        }
        contentView.findViewById<Button>(R.id.topRightColor).setOnClickListener {
            handleColorButton(
                    {cs: CustomColorScheme -> cs.topRightColor},
                    {cs: CustomColorScheme, color: Int -> cs.copy(topRightColor=color)})
        }
        contentView.findViewById<Button>(R.id.bottomLeftColor).setOnClickListener {
            handleColorButton(
                    {cs: CustomColorScheme -> cs.bottomLeftColor},
                    {cs: CustomColorScheme, color: Int -> cs.copy(bottomLeftColor=color)})
        }
        contentView.findViewById<Button>(R.id.bottomRightColor).setOnClickListener {
            handleColorButton(
                    {cs: CustomColorScheme -> cs.bottomRightColor},
                    {cs: CustomColorScheme, color: Int -> cs.copy(bottomRightColor=color)})
        }
        contentView.findViewById<Button>(R.id.backgroundColor).setOnClickListener {
            handleColorButton(
                    {cs: CustomColorScheme -> cs.backgroundColor},
                    {cs: CustomColorScheme, color: Int -> cs.copy(backgroundColor=color)})
        }
        contentView.findViewById<CheckBox>(R.id.solidCheckbox).setOnClickListener {
            handleTypeCheckbox(it as CheckBox)
        }
        contentView.findViewById<Button>(R.id.doneButton).setOnClickListener {
            visibility = View.GONE
        }
    }

    private fun handleColorButton(
            getColorFn: ((CustomColorScheme) -> Int),
            setColorFn: ((CustomColorScheme, Int) -> CustomColorScheme)) {
        val dlg = ColorPickerDialog.newBuilder().setColor(getColorFn(this.scheme)).create()

        dlg.setColorPickerDialogListener(object: ColorPickerDialogListener {
            override fun onDialogDismissed(dialogId: Int) {}

            override fun onColorSelected(dialogId: Int, color: Int) {
                scheme = setColorFn(scheme, color)
                changeCallback?.invoke(scheme)
            }
        })
        dlg.show(activity.fragmentManager, "color-picker-dialog")
    }

    private fun handleTypeCheckbox(cb: CheckBox) {
        val newType = if (cb.isChecked) CustomColorSchemeType.SOLID else CustomColorSchemeType.EDGE
        scheme = scheme.copy(type=newType)
        changeCallback?.invoke(scheme)
    }
}
