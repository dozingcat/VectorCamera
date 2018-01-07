package com.dozingcatsoftware.boojiecam.effect

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.RenderScript
import com.dozingcatsoftware.boojiecam.*

/**
 * Created by brian on 1/7/18.
 */
data class ColorScheme(val colorMap: Allocation, val paintFn: ((CameraImage, RectF) -> Paint?)) {

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
                    return ColorScheme(colorMap, { _, _ -> null })
                }
                "linear_gradient" -> {
                    val minColor = colorAsInt("minColor", "minEdgeColor")
                    val gradientStartColor = colorAsInt("gradientStartColor")
                    val gradientEndColor = colorAsInt("gradientEndColor")
                    val paintFn = fun(_: CameraImage, rect: RectF): Paint {
                        val p = Paint()
                        p.shader = LinearGradient(
                                rect.left, rect.top, rect.right, rect.bottom,
                                addAlpha(gradientStartColor), addAlpha(gradientEndColor),
                                Shader.TileMode.MIRROR)
                        return p
                    }
                    return ColorScheme(makeAlphaAllocation(rs, minColor), paintFn)
                }
                "radial_gradient" -> {
                    val minColor = colorAsInt("minColor", "minEdgeColor")
                    val centerColor = colorAsInt("centerColor")
                    val outerColor = colorAsInt("outerColor")
                    val paintFn = fun(_: CameraImage, rect: RectF): Paint {
                        val p = Paint()
                        p.shader = RadialGradient(
                                rect.width() / 2, rect.height() / 2,
                                maxOf(rect.width(), rect.height()) / 2f,
                                addAlpha(centerColor), addAlpha(outerColor), Shader.TileMode.MIRROR)
                        return p
                    }
                    return ColorScheme(makeAlphaAllocation(rs, minColor), paintFn)
                }
            }
            throw IllegalArgumentException("Unknown parameters: " + params)
        }
    }
}