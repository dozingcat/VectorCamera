package com.dozingcatsoftware.vectorcamera

import com.dozingcatsoftware.vectorcamera.effect.ColorMapMode

/**
 * User-editable parameters for a palette ColorMapEffect: whether it colors edges or solid
 * areas, and the list of colors (as ARGB ints) that brightness or edge strength maps through.
 */
data class CustomPaletteScheme(val mode: ColorMapMode, val colors: List<Int>) {

    fun toMap(): Map<String, Any> {
        return mapOf(
                "type" to this.mode.name,
                "colors" to this.colors
        )
    }

    /** Parameters for ColorMapEffect.fromParameters. */
    fun toEffectParameters(): Map<String, Any> {
        return mapOf(
                "colors" to mapOf(
                        "type" to "palette",
                        "stops" to colors.map {
                            listOf((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF)
                        }
                )
        )
    }

    companion object {
        const val MIN_COLORS = 2
        const val MAX_COLORS = 8

        /** The scheme that toEffectParameters produced, or null if the parameters don't fit. */
        fun fromEffectParameters(params: Map<String, Any>): CustomPaletteScheme? {
            val colors = params["colors"] as? Map<*, *> ?: return null
            if (colors["type"] != "palette") return null
            val stops = (colors["stops"] as? List<*>)?.map {colorFromRgbList(it) ?: return null} ?: return null
            if (stops.size < MIN_COLORS || stops.size > MAX_COLORS) return null
            return CustomPaletteScheme(ColorMapMode.fromParamValue(params["mode"]), stops)
        }

        fun fromMap(map: Map<String, Any>, defaultValues: CustomPaletteScheme): CustomPaletteScheme {
            val modeName = map.getOrElse("type", {defaultValues.mode.name}) as String
            val mode = try {ColorMapMode.valueOf(modeName)}
                       catch (ex: IllegalArgumentException) {defaultValues.mode}
            val colors = (map["colors"] as? List<*>)
                    ?.mapNotNull {(it as? Number)?.toInt()}
                    ?.take(MAX_COLORS)
            return CustomPaletteScheme(
                    mode,
                    if (colors != null && colors.size >= MIN_COLORS) colors else defaultValues.colors)
        }
    }
}
