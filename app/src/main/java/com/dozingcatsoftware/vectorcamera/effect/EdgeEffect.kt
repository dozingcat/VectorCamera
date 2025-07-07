package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.vectorcamera.*
import com.dozingcatsoftware.util.reuseOrCreate2dAllocation

/**
 * Effect that maps each pixel to an output color based on its edge strength.
 */
class EdgeEffect(private val rs: RenderScript,
                 private val effectParams: Map<String, Any>,
                 private val colorScheme: ColorScheme): Effect {

    private var outputAllocation: Allocation? = null
    private val script = ScriptC_edge(rs)

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        colorScheme.backgroundFn.invoke(cameraImage, canvas, rect)
    }

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        script._gWidth = cameraImage.width()
        script._gHeight = cameraImage.height()
        script._gMultiplier = minOf(4, maxOf(2, Math.round(cameraImage.width() / 480f)))
        script._gColorMap = colorScheme.colorMap

        outputAllocation = reuseOrCreate2dAllocation(outputAllocation,
                rs, Element::RGBA_8888,  cameraImage.width(), cameraImage.height())

        if (cameraImage.hasSingleYuv()) {
            script._gYuvInput = cameraImage.getSingleYuvAllocation()
        }
        else {
            script._gYuvInput = cameraImage.getPlanarYuvAllocations()!!.y
        }
        script.forEach_computeEdgeWithColorMap(outputAllocation)

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    companion object {
        const val EFFECT_NAME = "edge"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): EdgeEffect {
            // Hack for backwards compatibility.
            val p = params.getOrElse("colors", {params}) as Map<String, Any>
            return EdgeEffect(rs, params, ColorScheme.fromParameters(rs, p))
        }
    }
}
