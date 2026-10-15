package com.dozingcatsoftware.vectorcamera

/**
 * User-editable parameters for a ContourEffect: the fill colors (as ARGB ints) that the
 * brightness levels are spread through, dark to bright, and the color of the contour lines.
 */
data class CustomContourScheme(val fillColors: List<Int>, val lineColor: Int) {

    fun toMap(): Map<String, Any> {
        return mapOf(
                "fillColors" to this.fillColors,
                "lineColor" to this.lineColor
        )
    }

    /** Parameters for ContourEffect.fromParameters. */
    fun toEffectParameters(): Map<String, Any> {
        return mapOf(
                "colors" to mapOf(
                        "fill" to fillColors.map(::rgbList),
                        "line" to rgbList(lineColor)
                )
        )
    }

    companion object {
        const val MIN_COLORS = 2
        const val MAX_COLORS = 8

        fun fromMap(map: Map<String, Any>, defaultValues: CustomContourScheme): CustomContourScheme {
            val colors = (map["fillColors"] as? List<*>)
                    ?.mapNotNull {(it as? Number)?.toInt()}
                    ?.take(MAX_COLORS)
            val lineColor = (map["lineColor"] as? Number)?.toInt()
            return CustomContourScheme(
                    if (colors != null && colors.size >= MIN_COLORS) colors else defaultValues.fillColors,
                    lineColor ?: defaultValues.lineColor)
        }

        private fun rgbList(color: Int) =
                listOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
    }
}
