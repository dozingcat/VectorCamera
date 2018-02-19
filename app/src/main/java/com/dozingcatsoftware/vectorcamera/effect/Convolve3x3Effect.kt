package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicConvolve3x3
import com.dozingcatsoftware.vectorcamera.*
import com.dozingcatsoftware.util.reuseOrCreate2dAllocation

/**
 * Effect that performs a 3x3 convolve operation on the brightness of the input image.
 * The coefficients of the convolve are given as parameters.
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

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        colorScheme.backgroundFn.invoke(cameraImage, canvas, rect)
    }

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        convolveOutputAlloc = reuseOrCreate2dAllocation(convolveOutputAlloc,
                rs, Element::U8, cameraImage.width(), cameraImage.height())
        bitmapOutputAlloc = reuseOrCreate2dAllocation(bitmapOutputAlloc,
                rs, Element::RGBA_8888, cameraImage.width(), cameraImage.height())

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
        const val EFFECT_NAME = "convolve3x3"

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
