package com.dozingcatsoftware.boojiecam

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript

/**
 * Created by brian on 10/18/17.
 */
class EdgeEffect(val rs: RenderScript,
                 private val colorTable: Allocation,
                 private val paintFn: (CameraImage, RectF) -> Paint?): Effect {

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_edge? = null

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        if (script == null) {
            script = ScriptC_edge(rs)
        }
        val scr = this.script!!
        scr._gWidth = cameraImage.width()
        scr._gHeight = cameraImage.height()
        scr._gMultiplier = minOf(4, maxOf(2, Math.round(cameraImage.width() / 480f)))
        scr._gColorMap = colorTable

        if (!allocationHas2DSize(outputAllocation, cameraImage.width(), cameraImage.height())) {
            outputAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    cameraImage.width(), cameraImage.height())
        }

        if (cameraImage.singleYuvAllocation != null) {
            scr._gYuvInput = cameraImage.singleYuvAllocation
        }
        else {
            scr._gYuvInput = cameraImage.planarYuvAllocations!!.y
        }
        scr.forEach_computeEdgeWithColorMap(outputAllocation)

        val resultBitmap = Bitmap.createBitmap(
                cameraImage.width(), cameraImage.height(), Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    override fun createPaintFn(camAllocation: CameraImage): (RectF) -> Paint? {
        return {rect -> paintFn(camAllocation, rect)}
    }

    companion object {
        fun withFixedColors(rs: RenderScript,
                            minEdgeColor: Int, maxEdgeColor: Int): EdgeEffect {
            return EdgeEffect(
                    rs, makeAllocationColorMap(rs, minEdgeColor, maxEdgeColor), {_, _ -> null})
        }

        fun withLinearGradient(
                rs: RenderScript, minEdgeColor: Int,
                gradientStartColor: Int, gradientEndColor: Int): EdgeEffect {
            val paintFn = fun(_: CameraImage, rect: RectF): Paint {
                val p = Paint()
                p.shader = LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        addAlpha(gradientStartColor), addAlpha(gradientEndColor),
                        Shader.TileMode.MIRROR)
                return p
            }
            return EdgeEffect(rs, makeAlphaAllocation(rs, minEdgeColor), paintFn)
        }

        fun withRadialGradient(
                rs: RenderScript, minEdgeColor: Int,
                centerColor: Int, outerColor: Int): EdgeEffect {
            val paintFn = fun(_: CameraImage, rect: RectF): Paint {
                val p = Paint()
                p.shader = RadialGradient(
                        rect.width() / 2, rect.height() / 2,
                        maxOf(rect.width(), rect.height()) / 2f,
                        addAlpha(centerColor), addAlpha(outerColor), Shader.TileMode.MIRROR)
                return p
            }
            return EdgeEffect(rs, makeAlphaAllocation(rs, minEdgeColor), paintFn)
        }

    }
}