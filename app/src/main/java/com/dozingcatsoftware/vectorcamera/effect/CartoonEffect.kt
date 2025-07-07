package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.renderscript.*
import com.dozingcatsoftware.util.ScriptC_planar_yuv_to_rgba
import com.dozingcatsoftware.vectorcamera.*
import com.dozingcatsoftware.util.reuseOrCreate2dAllocation
import kotlin.math.roundToInt

/**
 * Effect that produces a cartoon-like image by blurring and reducing the color space.
 */
class CartoonEffect(val rs: RenderScript): Effect {

    private var outputAllocation: Allocation? = null
    // private val script = ScriptC_edge_color(rs)
    private var blurredAllocation: Allocation? = null
    private var blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

    private var rgbAllocation: Allocation? = null
    private var yuvToRgbScript = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    private var planarYuvToRgbScript = ScriptC_planar_yuv_to_rgba(rs)

    private var toonAllocation: Allocation? = null
    private var toonLutScript = ScriptIntrinsicLUT.create(rs, Element.U8_4(rs))

    override fun effectName() = EFFECT_NAME

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        rgbAllocation = reuseOrCreate2dAllocation(rgbAllocation,
                rs, Element::U8_4, cameraImage.width(), cameraImage.height())
        if (cameraImage.hasPlanarYuv()) {
            val planarAllocs = cameraImage.getPlanarYuvAllocations()!!
            planarYuvToRgbScript._yInputAlloc = planarAllocs.y
            planarYuvToRgbScript._uInputAlloc = planarAllocs.u
            planarYuvToRgbScript._vInputAlloc = planarAllocs.v
            planarYuvToRgbScript.forEach_convertToRgba(rgbAllocation)
        }
        else {
            yuvToRgbScript.setInput(cameraImage.getSingleYuvAllocation())
            yuvToRgbScript.forEach(rgbAllocation)
        }

        blurredAllocation = reuseOrCreate2dAllocation(blurredAllocation,
                rs, Element::RGBA_8888, cameraImage.width(), cameraImage.height())
        blurScript.setRadius(4f)
        blurScript.setInput(rgbAllocation)
        blurScript.forEach(blurredAllocation)

        // Reduce to 2 bits for each color component. Possible RGB values are (0, 85, 170, 255).
        toonAllocation = reuseOrCreate2dAllocation(blurredAllocation,
                rs, Element::RGBA_8888, cameraImage.width(), cameraImage.height())
        for (index in 0 until 256) {
            val mapval = (index / 85.0).roundToInt() * 85
            toonLutScript.setRed(index, mapval)
            toonLutScript.setGreen(index, mapval)
            toonLutScript.setBlue(index, mapval)
        }
        toonLutScript.forEach(blurredAllocation, toonAllocation)

        // Do convolution to find edges and blend?
        outputAllocation = toonAllocation

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    companion object {
        const val EFFECT_NAME = "cartoon"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): CartoonEffect {
            return CartoonEffect(rs)
        }
    }
}
