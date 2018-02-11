package com.dozingcatsoftware.boojiecam.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.boojiecam.*
import com.dozingcatsoftware.util.allocationHas2DSize
import com.dozingcatsoftware.util.create2dAllocation

/**
 * Created by brian on 10/29/17.
 */
class SolidColorEffect(val rs: RenderScript,
                       private val effectParams: Map<String, Any>,
                       private val colorScheme: ColorScheme): Effect {

    private var outputAllocation: Allocation? = null
    private val script = ScriptC_solid(rs)

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun drawBackground(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
        colorScheme.backgroundFn?.invoke(cameraImage, canvas, rect)
    }

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        if (cameraImage.planarYuvAllocations != null) {
            script._yuvInput = cameraImage.planarYuvAllocations.y
        }
        else {
            script._yuvInput = cameraImage.singleYuvAllocation
        }
        script._colorMap = colorScheme.colorMap

        if (!allocationHas2DSize(outputAllocation, cameraImage.width(), cameraImage.height())) {
            outputAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    cameraImage.width(), cameraImage.height())
        }

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
