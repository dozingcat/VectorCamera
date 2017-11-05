package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.renderscript.*

/**
 * Created by brian on 10/16/17.
 */
class EdgeColorAllocationProcessor(rs: RenderScript): CameraAllocationProcessor(rs) {

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_edge_color? = null

    override fun createBitmap(camAllocation: CameraImage): Bitmap {
        if (script == null) {
            script = ScriptC_edge_color(rs)
        }
        val allocation = camAllocation.allocation!!
        script!!._gYuvInput = allocation
        script!!._gWidth = allocation.type.x
        script!!._gHeight = allocation.type.y
        script!!._gMultiplier = minOf(4, maxOf(2, Math.round(allocation.type.x / 480f)))

        if (!allocationHas2DSize(outputAllocation, allocation.type.x, allocation.type.y)) {
            outputAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    allocation.type.x, allocation.type.y);
        }

        script!!.forEach_setBrightnessToEdgeStrength(outputAllocation)

        val resultBitmap = Bitmap.createBitmap(
                allocation.type.x, allocation.type.y, Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }
 }
