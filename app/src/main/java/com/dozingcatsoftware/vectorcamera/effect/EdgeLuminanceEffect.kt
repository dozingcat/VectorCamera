package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.renderscript.*
import com.dozingcatsoftware.vectorcamera.*
import com.dozingcatsoftware.util.reuseOrCreate2dAllocation

/**
 * Effect that preserves the "color" of each pixel as given by its U and V values, but replaces its
 * brightness (Y value) with its edge strength.
 */
class EdgeLuminanceEffect(val rs: RenderScript): Effect {

    private var outputAllocation: Allocation? = null
    private val script = ScriptC_edge_color(rs)
    private var blurredAllocation: Allocation? = null
    private var blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

    private var rgbAllocation: Allocation? = null
    private var yuvToRgbScript = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    private var toonAllocation: Allocation? = null
    private var toonLutScript = ScriptIntrinsicLUT.create(rs, Element.U8_4(rs))

    override fun effectName() = EFFECT_NAME

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        rgbAllocation = reuseOrCreate2dAllocation(rgbAllocation,
                rs, Element::U8_4, cameraImage.width(), cameraImage.height())
        yuvToRgbScript.setInput(cameraImage.singleYuvAllocation!!)
        yuvToRgbScript.forEach(rgbAllocation)

        blurredAllocation = reuseOrCreate2dAllocation(blurredAllocation,
                rs, Element::RGBA_8888, cameraImage.width(), cameraImage.height())
        blurScript.setRadius(4f)
        blurScript.setInput(rgbAllocation)
        blurScript.forEach(blurredAllocation)

        toonAllocation = reuseOrCreate2dAllocation(blurredAllocation,
                rs, Element::RGBA_8888, cameraImage.width(), cameraImage.height())
        for (index in 0 until 256) {
            val mapval = (index / 85) * 85
            toonLutScript.setRed(index, mapval)
            toonLutScript.setGreen(index, mapval)
            toonLutScript.setBlue(index, mapval)
        }
        toonLutScript.forEach(blurredAllocation, toonAllocation)

        // Do convolution to find edges and blend?

        /*
        blurScript.setInput(blurredAllocation1)
        blurScript.forEach(blurredAllocation2)

        for (i in 0 until 5) {
            blurScript.setInput(blurredAllocation2)
            blurScript.forEach(blurredAllocation1)

            blurScript.setInput(blurredAllocation1)
            blurScript.forEach(blurredAllocation2)
        }


        outputAllocation = reuseOrCreate2dAllocation(outputAllocation,
                rs, Element::RGBA_8888, cameraImage.width(), cameraImage.height())
        script._gWidth = cameraImage.width()
        script._gHeight = cameraImage.height()
        script._gMultiplier = minOf(4, maxOf(2, Math.round(cameraImage.width() / 480f)))
        script._gBlurredInput = blurredAllocation1

        if (cameraImage.planarYuvAllocations != null) {
            script._gYInput = cameraImage.planarYuvAllocations.y
            script._gUInput = cameraImage.planarYuvAllocations.u
            script._gVInput = cameraImage.planarYuvAllocations.v
            script.forEach_setBrightnessToEdgeStrength_planar(outputAllocation)
        }
        else {
            script._gYuvInput = cameraImage.singleYuvAllocation!!
            script.forEach_setBrightnessToEdgeStrength(outputAllocation)
        }
        */
        outputAllocation = toonAllocation

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    companion object {
        val EFFECT_NAME = "edge_luminance"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): EdgeLuminanceEffect {
            return EdgeLuminanceEffect(rs)
        }
    }
 }
