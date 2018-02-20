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
    private var script = ScriptC_edge_color(rs)

    override fun effectName() = EFFECT_NAME

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        outputAllocation = reuseOrCreate2dAllocation(outputAllocation,
                rs, Element::RGBA_8888, cameraImage.width(), cameraImage.height())
        script._gWidth = cameraImage.width()
        script._gHeight = cameraImage.height()
        script._gMultiplier = minOf(4, maxOf(2, Math.round(cameraImage.width() / 480f)))

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
