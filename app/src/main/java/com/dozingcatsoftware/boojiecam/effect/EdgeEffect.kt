package com.dozingcatsoftware.boojiecam.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.boojiecam.*
import com.dozingcatsoftware.util.allocationHas2DSize
import com.dozingcatsoftware.util.create2dAllocation

class EdgeEffect(private val rs: RenderScript,
                 private val effectParams: Map<String, Any>,
                 private val colorScheme: ColorScheme): Effect {

    private var outputAllocation: Allocation? = null
    private val script = ScriptC_edge(rs)

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        script._gWidth = cameraImage.width()
        script._gHeight = cameraImage.height()
        script._gMultiplier = minOf(4, maxOf(2, Math.round(cameraImage.width() / 480f)))
        script._gColorMap = colorScheme.colorMap

        if (!allocationHas2DSize(outputAllocation, cameraImage.width(), cameraImage.height())) {
            outputAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    cameraImage.width(), cameraImage.height())
        }

        if (cameraImage.singleYuvAllocation != null) {
            script._gYuvInput = cameraImage.singleYuvAllocation
        }
        else {
            script._gYuvInput = cameraImage.planarYuvAllocations!!.y
        }
        script.forEach_computeEdgeWithColorMap(outputAllocation)

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    override fun createPaintFn(cameraImage: CameraImage): (RectF) -> Paint? {
        return {rect -> colorScheme.paintFn(cameraImage, rect)}
    }

    companion object {
        val EFFECT_NAME = "edge"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): EdgeEffect {
            // Hack for backwards compatibility.
            val p = params.getOrElse("colors", {params}) as Map<String, Any>
            return EdgeEffect(rs, params, ColorScheme.fromParameters(rs, p))
        }
    }
}
