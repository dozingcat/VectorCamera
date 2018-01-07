package com.dozingcatsoftware.boojiecam.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.boojiecam.*

/**
 * Created by brian on 10/29/17.
 */
class SolidColorEffect(val rs: RenderScript,
                       private val effectParams: Map<String, Any>,
                       private val colorScheme: ColorScheme): Effect {

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_solid? = null

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        if (script == null) {
            script = ScriptC_solid(rs)
        }
        val scr = script!!

        if (cameraImage.planarYuvAllocations != null) {
            scr._yuvInput = cameraImage.planarYuvAllocations.y
        }
        else {
            scr._yuvInput = cameraImage.singleYuvAllocation
        }
        scr._colorMap = colorScheme.colorMap

        if (!allocationHas2DSize(outputAllocation, cameraImage.width(), cameraImage.height())) {
            outputAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    cameraImage.width(), cameraImage.height())
        }

        scr.forEach_computeColor(outputAllocation)

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    override fun createPaintFn(cameraImage: CameraImage): (RectF) -> Paint? {
        return {rect -> colorScheme.paintFn(cameraImage, rect)}
    }

    companion object {
        val EFFECT_NAME = "solid_color"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): SolidColorEffect {
            // Hack for backwards compatibility.
            val p = params.getOrElse("colors", {params}) as Map<String, Any>
            return SolidColorEffect(rs, params, ColorScheme.fromParameters(rs, p))
        }
    }
}