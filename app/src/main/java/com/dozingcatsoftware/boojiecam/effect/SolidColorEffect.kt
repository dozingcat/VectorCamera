package com.dozingcatsoftware.boojiecam.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.dozingcatsoftware.boojiecam.*

/**
 * Created by brian on 10/29/17.
 */
class SolidColorEffect(val rs: RenderScript,
                       private val effectParams: Map<String, Any>,
                       private val colorTable: Allocation,
                       private val paintFn: (CameraImage, RectF) -> Paint?): Effect {

    private var outputAllocation: Allocation? = null
    private var script: ScriptC_solid? = null

    override fun effectName() = EFFECT_NAME

    override fun effectParameters() = effectParams

    override fun createBitmap(cameraImage: CameraImage): Bitmap {
        if (script == null) {
            script = ScriptC_solid(rs)
        }
        val scr = script!!

        if (cameraImage.planarYuvAllocations != null) {
            scr._yuvInput = cameraImage.planarYuvAllocations.y
        }
        else {
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

    override fun createPaintFn(cameraImage: CameraImage): (RectF) -> Paint? {
        return {rect -> paintFn(cameraImage, rect)}
    }

    companion object {
        val EFFECT_NAME = "solid_color"

        fun fromParameters(rs: RenderScript, params: Map<String, Any>): SolidColorEffect {
            when (params["type"]) {
                "fixed" -> {
                    val minEdgeColor = intFromArgbList(params["minEdgeColor"] as List<Int>)
                    val maxEdgeColor = intFromArgbList(params["maxEdgeColor"] as List<Int>)
                    val colorMap = makeAllocationColorMap(rs, minEdgeColor, maxEdgeColor)
                    return SolidColorEffect(rs, params, colorMap, { _, _ -> null })
                }
                "linear_gradient" -> {
                    val minEdgeColor = intFromArgbList(params["minEdgeColor"] as List<Int>)
                    val gradientStartColor =
                            intFromArgbList(params["gradientStartColor"] as List<Int>)
                    val gradientEndColor =
                            intFromArgbList(params["gradientEndColor"] as List<Int>)
                    val paintFn = fun(_: CameraImage, rect: RectF): Paint {
                        val p = Paint()
                        p.shader = LinearGradient(
                                rect.left, rect.top, rect.right, rect.bottom,
                                addAlpha(gradientStartColor), addAlpha(gradientEndColor),
                                Shader.TileMode.MIRROR)
                        return p
                    }
                    return SolidColorEffect(
                            rs, params, makeAlphaAllocation(rs, minEdgeColor), paintFn)
                }
                "radial_gradient" -> {
                    val minEdgeColor = intFromArgbList(params["minEdgeColor"] as List<Int>)
                    val centerColor = intFromArgbList(params["centerColor"] as List<Int>)
                    val outerColor = intFromArgbList(params["outerColor"] as List<Int>)
                    val paintFn = fun(_: CameraImage, rect: RectF): Paint {
                        val p = Paint()
                        p.shader = RadialGradient(
                                rect.width() / 2, rect.height() / 2,
                                maxOf(rect.width(), rect.height()) / 2f,
                                addAlpha(centerColor), addAlpha(outerColor), Shader.TileMode.MIRROR)
                        return p
                    }
                    return SolidColorEffect(
                            rs, params, makeAlphaAllocation(rs, minEdgeColor), paintFn)
                }

            }
            throw IllegalArgumentException("Unknown parameters: " + params)
        }
    }
}