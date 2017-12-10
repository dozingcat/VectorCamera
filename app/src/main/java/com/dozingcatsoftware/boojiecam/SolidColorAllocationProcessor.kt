package com.dozingcatsoftware.boojiecam

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import java.io.ByteArrayInputStream

/**
 * Created by brian on 10/29/17.
 */
class SolidColorAllocationProcessor(rs: RenderScript,
                                    private val colorTable: Allocation,
                                    private val paintFn: (CameraImage, RectF) -> Paint?):
        CameraAllocationProcessor(rs) {

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_solid? = null

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        if (script == null) {
            script = ScriptC_solid(rs)
        }
        val scr = script!!

        if (cameraImage.planarYuvAllocations != null) {
            scr._yuvInput = cameraImage.planarYuvAllocations.y
        }
        else {
//            val bytes = flattenedYuvImageBytes(rs, cameraImage.singleYuvAllocation!!)
//            scr._yuvInput = PlanarYuvAllocations.fromInputStream(rs, ByteArrayInputStream(bytes),
//                    cameraImage.width(), cameraImage.height()).y
            scr._yuvInput = cameraImage.singleYuvAllocation
        }
        scr._colorMap = colorTable

        if (!allocationHas2DSize(outputAllocation, cameraImage.width(), cameraImage.height())) {
            outputAllocation = create2dAllocation(rs, Element::RGBA_8888,
                    cameraImage.width(), cameraImage.height())
        }

        scr.forEach_computeColor(outputAllocation)

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
                            minEdgeColor: Int, maxEdgeColor: Int): SolidColorAllocationProcessor {
            return SolidColorAllocationProcessor(
                    rs, makeAllocationColorMap(rs, minEdgeColor, maxEdgeColor), {_, _ -> null})
        }

        fun withLinearGradient(
                rs: RenderScript, minEdgeColor: Int,
                gradientStartColor: Int, gradientEndColor: Int): EdgeAllocationProcessor {
            val paintFn = fun(_: CameraImage, rect: RectF): Paint {
                val p = Paint()
                p.shader = LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        addAlpha(gradientStartColor), addAlpha(gradientEndColor),
                        Shader.TileMode.MIRROR)
                return p
            }
            return EdgeAllocationProcessor(rs, makeAlphaAllocation(rs, minEdgeColor), paintFn)
        }

        fun withRadialGradient(
                rs: RenderScript, minEdgeColor: Int,
                centerColor: Int, outerColor: Int): EdgeAllocationProcessor {
            val paintFn = fun(_: CameraImage, rect: RectF): Paint {
                val p = Paint()
                p.shader = RadialGradient(
                        rect.width() / 2, rect.height() / 2,
                        maxOf(rect.width(), rect.height()) / 2f,
                        addAlpha(centerColor), addAlpha(outerColor), Shader.TileMode.MIRROR)
                return p
            }
            return EdgeAllocationProcessor(rs, makeAlphaAllocation(rs, minEdgeColor), paintFn)
        }
    }
}