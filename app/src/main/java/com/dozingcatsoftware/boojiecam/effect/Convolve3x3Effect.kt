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
                        private val colorTable: Allocation,
                        private val paintFn: (CameraImage, RectF) -> Paint?): Effect {

    private var convolveOutputAlloc: Allocation? = null
    private var bitmapOutputAlloc: Allocation? = null
    private var convolveScript: ScriptIntrinsicConvolve3x3? = null
    private var colorMapScript: ScriptC_colormap? = null

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createPaintFn(cameraImage: CameraImage): (RectF) -> Paint? {
        return {rect -> paintFn(cameraImage, rect)}
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

        if (convolveScript == null) {
            convolveScript = ScriptIntrinsicConvolve3x3.create(rs, Element.U8(rs))
            convolveScript!!.setCoefficients(coefficients)
        }
        val convolveScr = convolveScript!!
        if (cameraImage.singleYuvAllocation != null) {
            convolveScr.setInput(cameraImage.singleYuvAllocation)
        }
        else {
            convolveScr.setInput(cameraImage.planarYuvAllocations!!.y)
        }
        convolveScr.forEach(convolveOutputAlloc)

        if (colorMapScript == null) {
            colorMapScript = ScriptC_colormap(rs)
        }
        val colorMapScr = colorMapScript!!
        colorMapScr._gColorMap = colorTable
        colorMapScr.forEach_applyColorMap(convolveOutputAlloc, bitmapOutputAlloc)

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        bitmapOutputAlloc!!.copyTo(resultBitmap)

        return resultBitmap
    }

    companion object {
        val EFFECT_NAME = "convolve3x3"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): Convolve3x3Effect {
            val colorMap = makeAllocationColorMap(rs,
                    Color.argb(255, 0, 0, 0),
                    Color.argb(255, 255, 255, 255))
            val coeffs = FloatArray(9)
            val paramList = params["coefficients"] as List<Number>
            if (paramList.size != 9) {
                throw IllegalArgumentException("Expected 9 coefficients, got ${paramList.size}")
            }
            for (i in 0 until 9) {
                coeffs[i] = paramList[i].toFloat()
            }
            return Convolve3x3Effect(rs, params, coeffs, colorMap, { _, _ -> null })
        }
    }
}