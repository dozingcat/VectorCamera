package com.dozingcatsoftware.vectorcamera

import com.dozingcatsoftware.vectorcamera.effect.ColorComponentSource

/**
 * User-editable parameters for a PermuteColorEffect: where each output color component comes
 * from, and whether to flip the hue.
 */
data class CustomPermuteScheme(
        val redSource: ColorComponentSource,
        val greenSource: ColorComponentSource,
        val blueSource: ColorComponentSource,
        val flipUV: Boolean = false) {

    fun toMap(): Map<String, Any> {
        return mapOf(
                "red" to redSource.name,
                "green" to greenSource.name,
                "blue" to blueSource.name,
                "flipUV" to flipUV
        )
    }

    /** Parameters in the form expected by PermuteColorEffect.fromParameters. */
    fun toEffectParameters(): Map<String, Any> = toMap()

    companion object {
        fun fromMap(map: Map<String, Any>, defaultValues: CustomPermuteScheme): CustomPermuteScheme {
            fun source(key: String, default: ColorComponentSource): ColorComponentSource {
                val name = map[key] as? String ?: return default
                return try {ColorComponentSource.valueOf(name)}
                       catch (ex: IllegalArgumentException) {default}
            }
            return CustomPermuteScheme(
                    source("red", defaultValues.redSource),
                    source("green", defaultValues.greenSource),
                    source("blue", defaultValues.blueSource),
                    map["flipUV"] as? Boolean ?: defaultValues.flipUV
            )
        }
    }
}
