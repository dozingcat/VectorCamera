package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript

/**
 * Created by brian on 12/11/17.
 */
enum class ColorComponentSource(val rsCode: Int) {
    RED(1),
    GREEN(2),
    BLUE(3),
    MIN(0),
    MAX(-1),
}

class PermuteColorEffect(
        val rs: RenderScript,
        val redSource: ColorComponentSource,
        val greenSource: ColorComponentSource,
        val blueSource: ColorComponentSource): Effect {

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_permute_colors? = null

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
        fun noOp(rs: RenderScript) = PermuteColorEffect(rs,
                ColorComponentSource.RED, ColorComponentSource.GREEN, ColorComponentSource.BLUE)

        fun rgbToGbr(rs: RenderScript) = PermuteColorEffect(rs,
                ColorComponentSource.GREEN, ColorComponentSource.BLUE, ColorComponentSource.RED)

        fun rgbToBrg(rs: RenderScript) = PermuteColorEffect(rs,
                ColorComponentSource.BLUE, ColorComponentSource.RED, ColorComponentSource.GREEN)
    }
}