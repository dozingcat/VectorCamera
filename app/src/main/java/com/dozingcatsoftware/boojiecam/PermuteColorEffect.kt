package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript

/**
 * Created by brian on 12/11/17.
 */
enum class ColorComponentSource(val rsCode: Int) {
    // These names may be stored in picture metadata; don't change without ensuring compatibility.
    RED(1),
    GREEN(2),
    BLUE(3),
    MIN(0),
    MAX(-1),
}

class PermuteColorEffect(
        private val rs: RenderScript,
        private val effectParams: Map<String, Any>,
        private val redSource: ColorComponentSource,
        private val greenSource: ColorComponentSource,
        private val blueSource: ColorComponentSource): Effect {

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_permute_colors? = null

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        if (!allocationHas2DSize(outputAllocation, cameraImage.width(), cameraImage.height())) {
            outputAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    cameraImage.width(), cameraImage.height())
        }
        if (script == null) {
            script = ScriptC_permute_colors(rs)
        }
        val scr = script!!
        scr._gRedSource = redSource.rsCode
        scr._gGreenSource = greenSource.rsCode
        scr._gBlueSource = blueSource.rsCode

        if (cameraImage.planarYuvAllocations != null) {
            scr._gYInput = cameraImage.planarYuvAllocations.y
            scr._gUInput = cameraImage.planarYuvAllocations.u
            scr._gVInput = cameraImage.planarYuvAllocations.v
            scr.forEach_permuteColors_planar(outputAllocation)
        }
        else {
            scr._gYuvInput = cameraImage.singleYuvAllocation!!
            scr.forEach_permuteColors(outputAllocation)
        }

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    companion object {
        val EFFECT_NAME = "permute_colors"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): PermuteColorEffect {
            val redSource = ColorComponentSource.valueOf(params["red"] as String)
            val greenSource = ColorComponentSource.valueOf(params["green"] as String)
            val blueSource = ColorComponentSource.valueOf(params["blue"] as String)
            return PermuteColorEffect(rs, params, redSource, greenSource, blueSource)
        }

        fun noOp(rs: RenderScript) = fromParameters(rs, mapOf(
                "red" to ColorComponentSource.RED.toString(),
                "green" to ColorComponentSource.GREEN.toString(),
                "blue" to ColorComponentSource.BLUE.toString()
        ))

        fun rgbToGbr(rs: RenderScript) = fromParameters(rs, mapOf(
                "red" to ColorComponentSource.GREEN.toString(),
                "green" to ColorComponentSource.BLUE.toString(),
                "blue" to ColorComponentSource.RED.toString()
        ))

        fun rgbToBrg(rs: RenderScript) = fromParameters(rs, mapOf(
                "red" to ColorComponentSource.BLUE.toString(),
                "green" to ColorComponentSource.RED.toString(),
                "blue" to ColorComponentSource.GREEN.toString()
        ))
    }
}