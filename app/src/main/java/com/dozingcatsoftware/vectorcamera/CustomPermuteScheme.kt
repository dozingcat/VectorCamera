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
        /** The scheme that toEffectParameters produced, or null if the parameters don't fit. */
        fun fromEffectParameters(params: Map<String, Any>): CustomPermuteScheme? {
            fun source(key: String): ColorComponentSource? {
                val name = params[key] as? String ?: return null
                return try {ColorComponentSource.valueOf(name)}
                       catch (ex: IllegalArgumentException) {null}
            }
            return CustomPermuteScheme(
                    source("red") ?: return null,
                    source("green") ?: return null,
                    source("blue") ?: return null,
                    params["flipUV"] as? Boolean ?: false)
        }

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
