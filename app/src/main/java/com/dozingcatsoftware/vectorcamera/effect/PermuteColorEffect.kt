package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.vectorcamera.*
import com.dozingcatsoftware.util.reuseOrCreate2dAllocation

enum class ColorComponentSource(val rsCode: Int) {
    // These names may be stored in picture metadata; don't change without ensuring compatibility.
    RED(1),
    GREEN(2),
    BLUE(3),
    MIN(0),
    MAX(-1),
}

/**
 * Effect that swaps the red/green/blue and/or U/V components of an image.
 */
class PermuteColorEffect(
        private val rs: RenderScript,
        private val effectParams: Map<String, Any>,
        private val redSource: ColorComponentSource,
        private val greenSource: ColorComponentSource,
        private val blueSource: ColorComponentSource,
        private val flipUV: Boolean): Effect {

    private var outputAllocation: Allocation? = null
    private val script = ScriptC_permute_colors(rs)

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        outputAllocation = reuseOrCreate2dAllocation(outputAllocation,
                rs, Element::RGBA_8888, cameraImage.width(), cameraImage.height())
        script._gRedSource = redSource.rsCode
        script._gGreenSource = greenSource.rsCode
        script._gBlueSource = blueSource.rsCode
        script._gFlipUV = flipUV

        if (cameraImage.planarYuvAllocations != null) {
            script._gYInput = cameraImage.planarYuvAllocations.y
            script._gUInput = cameraImage.planarYuvAllocations.u
            script._gVInput = cameraImage.planarYuvAllocations.v
            script.forEach_permuteColors_planar(outputAllocation)
        }
        else {
            script._gYuvInput = cameraImage.singleYuvAllocation!!
            script.forEach_permuteColors(outputAllocation)
        }

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    companion object {
        const val EFFECT_NAME = "permute_colors"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): PermuteColorEffect {
            val redSource = ColorComponentSource.valueOf(params["red"] as String)
            val greenSource = ColorComponentSource.valueOf(params["green"] as String)
            val blueSource = ColorComponentSource.valueOf(params["blue"] as String)
            val flipUV = params.getOrElse("flipUV", {false}) as Boolean
            return PermuteColorEffect(rs, params, redSource, greenSource, blueSource, flipUV)
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

        fun flipUV(rs: RenderScript) = fromParameters(rs, mapOf(
                "red" to ColorComponentSource.RED.toString(),
                "green" to ColorComponentSource.GREEN.toString(),
                "blue" to ColorComponentSource.BLUE.toString(),
                "flipUV" to true
        ))
    }
}