package com.dozingcatsoftware.vectorcamera

import com.dozingcatsoftware.vectorcamera.effect.ColorMapMode

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

    companion object {
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
