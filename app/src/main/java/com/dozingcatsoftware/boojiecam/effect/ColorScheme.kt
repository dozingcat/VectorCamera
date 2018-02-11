package com.dozingcatsoftware.boojiecam.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.RenderScript
import com.dozingcatsoftware.boojiecam.*
import com.dozingcatsoftware.util.addAlpha
import com.dozingcatsoftware.util.intFromArgbList
import com.dozingcatsoftware.util.makeAllocationColorMap
import com.dozingcatsoftware.util.makeAlphaAllocation

/**
 * Created by brian on 1/7/18.
 */
// Not paint function, instead backgroundFn taking Canvas and RectF?
data class ColorScheme(val colorMap: Allocation,
                       val backgroundFn: (CameraImage, Canvas, RectF) -> Unit) {

    companion object {
        fun fromParameters(rs: RenderScript, params: Map<String, Any>): ColorScheme {

            fun colorAsInt(vararg keys: String): Int {
                for (k in keys) {
                    if (params.containsKey(k)) {
                        return intFromArgbList(params[k] as List<Int>)
                    }
                }
                throw IllegalArgumentException("Key not found: ${keys}")
            }

            when (params["type"]) {
                "fixed" -> {
                    val minColor = colorAsInt("minColor", "minEdgeColor")
                    val maxColor = colorAsInt("maxColor", "maxEdgeColor")
                    val colorMap = makeAllocationColorMap(rs, minColor, maxColor)
                    return ColorScheme(colorMap, { _, _, _ -> null })
                }
                "linear_gradient" -> {
                    val minColor = colorAsInt("minColor", "minEdgeColor")
                    val gradientStartColor = colorAsInt("gradientStartColor")
                    val gradientEndColor = colorAsInt("gradientEndColor")
                    val backgroundFn = fun(_: CameraImage, canvas: Canvas, rect: RectF) {
                        val p = Paint()
                        p.shader = LinearGradient(
                                rect.left, rect.top, rect.right, rect.bottom,
                                addAlpha(gradientStartColor), addAlpha(gradientEndColor),
                                Shader.TileMode.MIRROR)
                        canvas.drawRect(rect, p)
                    }
                    return ColorScheme(makeAlphaAllocation(rs, minColor), backgroundFn)
                }
                "radial_gradient" -> {
                    val minColor = colorAsInt("minColor", "minEdgeColor")
                    val centerColor = colorAsInt("centerColor")
                    val outerColor = colorAsInt("outerColor")
                    val backgroundFn = fun(_: CameraImage, canvas: Canvas, rect: RectF) {
                        val p = Paint()
                        p.shader = RadialGradient(
                                rect.width() / 2, rect.height() / 2,
                                maxOf(rect.width(), rect.height()) / 2f,
                                addAlpha(centerColor), addAlpha(outerColor), Shader.TileMode.MIRROR)
                        canvas.drawRect(rect, p)
                    }
                    return ColorScheme(makeAlphaAllocation(rs, minColor), backgroundFn)
                }
                "grid_gradient" -> {
                    val minColor = colorAsInt("minColor")
                    val gridColors = params["grid"] as List<List<List<Int>>>
                    val speedX = params.getOrDefault("speedX", 0) as Number
                    val speedY = params.getOrDefault("speedY", 0) as Number
                    val sizeX = params.getOrDefault("sizeX", 1) as Number
                    val sizeY = params.getOrDefault("sizeY", 1) as Number
                    val pixPerCell = params.getOrDefault(
                            "pixelsPerCell", Animated2dGradient.DEFAULT_PIXELS_PER_CELL) as Number
                    val gradient = Animated2dGradient(gridColors, speedX.toInt(), speedY.toInt(),
                            sizeX.toFloat(), sizeY.toFloat(), pixPerCell.toInt())
                    val backgroundFn = fun(cameraImage: CameraImage, canvas: Canvas, rect: RectF) {
                        gradient.drawToCanvas(canvas, rect, cameraImage.timestamp)
                    }
                    return ColorScheme(makeAlphaAllocation(rs, minColor), backgroundFn)
                }
            }
            throw IllegalArgumentException("Unknown parameters: " + params)
        }
    }
}