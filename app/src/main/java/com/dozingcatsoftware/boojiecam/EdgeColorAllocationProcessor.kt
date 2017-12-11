package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.renderscript.*

/**
 * Created by brian on 10/16/17.
 */
class EdgeColorAllocationProcessor(rs: RenderScript): CameraAllocationProcessor(rs) {

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_edge_color? = null

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        if (!allocationHas2DSize(outputAllocation, cameraImage.width(), cameraImage.height())) {
            outputAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    cameraImage.width(), cameraImage.height())
        }
        if (script == null) {
            script = ScriptC_edge_color(rs)
        }
        val scr = script!!
        scr._gWidth = cameraImage.width()
        scr._gHeight = cameraImage.height()
        scr._gMultiplier = minOf(4, maxOf(2, Math.round(cameraImage.width() / 480f)))

        if (cameraImage.planarYuvAllocations != null) {
            scr._gYInput = cameraImage.planarYuvAllocations.y
            scr._gUInput = cameraImage.planarYuvAllocations.u
            scr._gVInput = cameraImage.planarYuvAllocations.v
            scr.forEach_setBrightnessToEdgeStrength_planar(outputAllocation)
        }
        else {
            scr._gYuvInput = cameraImage.singleYuvAllocation!!
            scr.forEach_setBrightnessToEdgeStrength(outputAllocation)
        }


        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }
 }
