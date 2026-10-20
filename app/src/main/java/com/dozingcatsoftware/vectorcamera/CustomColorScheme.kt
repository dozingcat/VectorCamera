package com.dozingcatsoftware.vectorcamera

import android.graphics.Color
import com.dozingcatsoftware.vectorcamera.effect.ColorMapMode

/** The ARGB color for an [r, g, b] list as stored in effect parameters, or null if it isn't one. */
internal fun colorFromRgbList(value: Any?): Int? {
    val components = (value as? List<*>)?.mapNotNull {(it as? Number)?.toInt()} ?: return null
    if (components.size != 3) return null
    return Color.rgb(components[0], components[1], components[2])
}

/**
 * User-editable parameters for a ColorMapEffect: whether it colors edges or solid areas, the
 * background color, and the four corner colors of the gradient drawn over it.
 */
data class CustomColorScheme(
        val mode: ColorMapMode, val backgroundColor: Int,
        val topLeftColor: Int, val topRightColor: Int,
        val bottomLeftColor: Int, val bottomRightColor: Int) {

    fun toMap(): Map<String, Any> {
        return mapOf(
                "type" to this.mode.name,
                "background" to this.backgroundColor,
                "topLeft" to this.topLeftColor,
                "topRight" to this.topRightColor,
                "bottomLeft" to this.bottomLeftColor,
                "bottomRight" to this.bottomRightColor
        )
    }

    /** Parameters for ColorMapEffect.fromParameters: a 2x2 gradient grid over the background. */
    fun toEffectParameters(pixelsPerCell: Int): Map<String, Any> {
        fun rgb(color: Int) = listOf(Color.red(color), Color.green(color), Color.blue(color))
        return mapOf(
                "type" to "grid_gradient",
                "minColor" to rgb(backgroundColor),
                "grid" to listOf(listOf(
                        rgb(topLeftColor) + rgb(topRightColor) + rgb(bottomLeftColor) + rgb(bottomRightColor))),
                "pixelsPerCell" to pixelsPerCell
        )
    }

    companion object {
        /**
         * The scheme that toEffectParameters produced, from a saved effect's parameters, or null
         * if the parameters aren't a 2x2 gradient grid (e.g. a built-in gradient effect).
         */
        fun fromEffectParameters(params: Map<String, Any>): CustomColorScheme? {
            val colors = (params["colors"] as? Map<*, *>) ?: params
            if (colors["type"] != "grid_gradient") return null
            val background = colorFromRgbList(colors["minColor"]) ?: return null
            val cell = ((colors["grid"] as? List<*>)?.singleOrNull() as? List<*>)?.singleOrNull()
            val components = (cell as? List<*>)?.mapNotNull {(it as? Number)?.toInt()} ?: return null
            if (components.size != 12) return null
            fun corner(i: Int) = Color.rgb(components[3 * i], components[3 * i + 1], components[3 * i + 2])
            return CustomColorScheme(ColorMapMode.fromParamValue(params["mode"]), background,
                    corner(0), corner(1), corner(2), corner(3))
        }

        fun fromMap(map: Map<String, Any>, defaultValues: CustomColorScheme): CustomColorScheme {
            val modeName = map.getOrElse("type", {defaultValues.mode.name}) as String
            val mode = try {ColorMapMode.valueOf(modeName)}
                       catch (ex: IllegalArgumentException) {ColorMapMode.EDGE}
            return CustomColorScheme(
                    mode,
                    map.getOrElse("background", {defaultValues.backgroundColor}) as Int,
                    map.getOrElse("topLeft", {defaultValues.topLeftColor}) as Int,
                    map.getOrElse("topRight", {defaultValues.topRightColor}) as Int,
                    map.getOrElse("bottomLeft", {defaultValues.bottomLeftColor}) as Int,
                    map.getOrElse("bottomRight", {defaultValues.bottomRightColor}) as Int
            )
        }
    }
}
