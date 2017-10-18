package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.renderscript.*

/**
 * Created by brian on 10/16/17.
 */
class EdgeColorAllocationProcessor(rs: RenderScript): CameraAllocationProcessor(rs) {

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_edge_color? = null

    override fun createBitmap(camAllocation: CameraAllocation): Bitmap {
        if (script == null) {
            script = ScriptC_edge_color(rs)
        }
        val allocation = camAllocation.allocation
        script!!._gYuvInput = allocation
        script!!._gWidth = allocation.type.x
        script!!._gHeight = allocation.type.y

        if (outputAllocation == null ||
                outputAllocation!!.type.x != allocation.type.x ||
                outputAllocation!!.type.y != allocation.type.y) {
            val outputTypeBuilder = Type.Builder(rs, Element.RGBA_8888(rs))
            outputTypeBuilder.setX(allocation.type.x)
            outputTypeBuilder.setY(allocation.type.y)
            outputAllocation = Allocation.createTyped(rs, outputTypeBuilder.create(),
                    Allocation.USAGE_SCRIPT)
        }

        script!!.forEach_setBrightnessToEdgeStrength(outputAllocation)

        val resultBitmap = Bitmap.createBitmap(
                allocation.type.x, allocation.type.y, Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        // val canvas = Canvas(resultBitmap)
        // val ts = camAllocation.timestamp
        // canvas.drawColor(Color.argb(255, (ts % 255).toInt(), 0, 0))

        return resultBitmap
    }
}
