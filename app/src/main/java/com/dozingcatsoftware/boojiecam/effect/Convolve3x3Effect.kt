package com.dozingcatsoftware.boojiecam.effect

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicConvolve3x3
import com.dozingcatsoftware.boojiecam.*

/**
 * Created by brian on 1/6/18.
 */
class Convolve3x3Effect(private val rs: RenderScript,
                        private val effectParams: Map<String, Any>,
                        private val coefficients: FloatArray,
                        private val colorScheme: ColorScheme): Effect {

    private var convolveOutputAlloc: Allocation? = null
    private var bitmapOutputAlloc: Allocation? = null
    private val convolveScript = ScriptIntrinsicConvolve3x3.create(rs, Element.U8(rs))
    private var colorMapScript: ScriptC_colormap? = null

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createPaintFn(cameraImage: CameraImage): (RectF) -> Paint? {
        return {rect -> colorScheme.paintFn(cameraImage, rect)}
    }

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        if (!allocationHas2DSize(convolveOutputAlloc, cameraImage.width(), cameraImage.height())) {
            convolveOutputAlloc = create2dAllocation(rs, Element::U8,
                    cameraImage.width(), cameraImage.height())
        }
        if (!allocationHas2DSize(bitmapOutputAlloc, cameraImage.width(), cameraImage.height())) {
            bitmapOutputAlloc = create2dAllocation(rs, Element::RGBA_8888,
                    cameraImage.width(), cameraImage.height())
        }

        convolveScript.setCoefficients(coefficients)
        if (cameraImage.singleYuvAllocation != null) {
            convolveScript.setInput(cameraImage.singleYuvAllocation)
        }
        else {
            convolveScript.setInput(cameraImage.planarYuvAllocations!!.y)
        }
        convolveScript.forEach(convolveOutputAlloc)

        if (colorMapScript == null) {
            colorMapScript = ScriptC_colormap(rs)
        }
        val colorMapScr = colorMapScript!!
        colorMapScr._gColorMap = colorScheme.colorMap
        colorMapScr.forEach_applyColorMap(convolveOutputAlloc, bitmapOutputAlloc)

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        bitmapOutputAlloc!!.copyTo(resultBitmap)

        return resultBitmap
    }

    companion object {
        val EFFECT_NAME = "convolve3x3"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): Convolve3x3Effect {
            val coeffs = FloatArray(9)
            val paramList = params["coefficients"] as List<Number>
            if (paramList.size != 9) {
                throw IllegalArgumentException("Expected 9 coefficients, got ${paramList.size}")
            }
            for (i in 0 until 9) {
                coeffs[i] = paramList[i].toFloat()
            }
            // Hack for backwards compatibility.
            val p = params.getOrElse("colors", {params}) as Map<String, Any>
            return Convolve3x3Effect(rs, params, coeffs, ColorScheme.fromParameters(rs, p))
        }
    }
}
