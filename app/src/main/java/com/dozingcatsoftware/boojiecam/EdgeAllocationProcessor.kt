package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type

/**
 * Created by brian on 10/18/17.
 */
class EdgeAllocationProcessor(rs: RenderScript): CameraAllocationProcessor(rs) {
    private var outputAllocation: Allocation? = null
    private var script: ScriptC_edge? = null

    override fun createBitmap(camAllocation: CameraAllocation): Bitmap {
        if (script == null) {
            script = ScriptC_edge(rs)
        }
        val allocation = camAllocation.allocation
        script!!._gYuvInput = allocation
        script!!._gWidth = allocation.type.x
        script!!._gHeight = allocation.type.y
        script!!._gMultiplier = minOf(4, maxOf(2, Math.round(allocation.type.x / 480f))).toInt()

        if (outputAllocation == null ||
                outputAllocation!!.type.x != allocation.type.x ||
                outputAllocation!!.type.y != allocation.type.y) {
            val outputTypeBuilder = Type.Builder(rs, Element.RGBA_8888(rs))
            outputTypeBuilder.setX(allocation.type.x)
            outputTypeBuilder.setY(allocation.type.y)
            outputAllocation = Allocation.createTyped(rs, outputTypeBuilder.create(),
                    Allocation.USAGE_SCRIPT)
        }

        script!!.forEach_computeEdge(outputAllocation)

        val resultBitmap = Bitmap.createBitmap(
                allocation.type.x, allocation.type.y, Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }
}