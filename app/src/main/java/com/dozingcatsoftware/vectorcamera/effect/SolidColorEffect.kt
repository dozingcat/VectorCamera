package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.vectorcamera.*
import com.dozingcatsoftware.util.reuseOrCreate2dAllocation

/**
 * Effect that maps each pixel to an output color based on its brightness.
 */
class SolidColorEffect(val rs: RenderScript,
                       private val effectParams: Map<String, Any>,
                       private val colorScheme: ColorScheme): Effect {

    private var outputAllocation: Allocation? = null
    private val script = ScriptC_solid(rs)

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        colorScheme.backgroundFn.invoke(cameraImage, canvas, rect)
    }

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        val planarYuv = cameraImage.getPlanarYuvAllocations()!!
        script._yuvInput = planarYuv.y
        script._colorMap = colorScheme.colorMap

        outputAllocation = reuseOrCreate2dAllocation(outputAllocation,
                rs, Element::RGBA_8888, cameraImage.width(), cameraImage.height())

        script.forEach_computeColor(outputAllocation)

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    companion object {
        const val EFFECT_NAME = "solid_color"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): SolidColorEffect {
            // Hack for backwards compatibility.
            val p = params.getOrElse("colors", {params}) as Map<String, Any>
            return SolidColorEffect(rs, params, ColorScheme.fromParameters(rs, p))
        }
    }
}
