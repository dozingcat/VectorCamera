package com.dozingcatsoftware.vectorcamera

enum class CustomColorSchemeType {
    EDGE,
    SOLID,
}

data class CustomColorScheme(
        val type: CustomColorSchemeType, val backgroundColor: Int,
        val topLeftColor: Int, val topRightColor: Int,
        val bottomLeftColor: Int, val bottomRightColor: Int) {

    fun toMap(): Map<String, Any> {
        return mapOf(
                "type" to this.type.name,
                "background" to this.backgroundColor,
                "topLeft" to this.topLeftColor,
                "topRight" to this.topRightColor,
                "bottomLeft" to this.bottomLeftColor,
                "bottomRight" to this.bottomRightColor
        )
    }

    companion object {
        fun fromMap(map: Map<String, Any>, defaultValues: CustomColorScheme): CustomColorScheme {
            val typeName = map.getOrElse("type", {defaultValues.type.name}) as String
            val type = try {CustomColorSchemeType.valueOf(typeName)}
                       catch (ex: IllegalArgumentException) {CustomColorSchemeType.EDGE}
            return CustomColorScheme(
                    type,
                    map.getOrElse("background", {defaultValues.backgroundColor}) as Int,
                    map.getOrElse("topLeft", {defaultValues.topLeftColor}) as Int,
                    map.getOrElse("topRight", {defaultValues.topRightColor}) as Int,
                    map.getOrElse("bottomLeft", {defaultValues.bottomLeftColor}) as Int,
                    map.getOrElse("bottomRight", {defaultValues.bottomRightColor}) as Int
            )
        }
    }
}