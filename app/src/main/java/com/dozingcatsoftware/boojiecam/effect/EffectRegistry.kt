package com.dozingcatsoftware.boojiecam.effect

import android.renderscript.RenderScript

object EffectRegistry {

    val baseEffects = listOf<(RenderScript) -> Effect>(

            {rs -> EdgeLuminanceEffect(rs) },
            {rs -> PermuteColorEffect.noOp(rs) },
            {rs -> PermuteColorEffect.rgbToBrg(rs) },
            {rs -> PermuteColorEffect.rgbToGbr(rs) },

            {rs ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
            {rs ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 0, 0)
                        )
                ))
            },
            {rs ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 255, 0)
                        )
                ))
            },
            {rs ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 0, 255)
                        )
                ))
            },

            {rs ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 0)
                        )
                ))
            },
            {rs ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 255)
                        )
                ))
            },
            {rs ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "linear_gradient",
                                "minColor" to listOf(0, 0, 0),
                                "gradientStartColor" to listOf(0, 255, 0),
                                "gradientEndColor" to listOf(0, 0, 255)
                        )
                ))
            },
            {rs ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "radial_gradient",
                                "minColor" to listOf(25, 25, 112),
                                "centerColor" to listOf(255, 255, 0),
                                "outerColor" to listOf(255, 70, 0)
                        )
                ))
            },

            {rs ->
                Convolve3x3Effect.fromParameters(rs, mapOf(
                        "coefficients" to listOf(4, 2, 0, 2, 1, -2, 0, -2, -4),
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
            {rs ->
                Convolve3x3Effect.fromParameters(rs, mapOf(
                        "coefficients" to listOf(4, 2, 0, 2, 1, -2, 0, -2, -4),
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 0, 0)
                        )
                ))
            },
            {rs ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "text" to listOf(255, 255, 255),
                                "background" to listOf(0, 0, 0)
                        )
                ))
            },
            {rs ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "text" to listOf(0, 0, 0),
                                "background" to listOf(255, 255, 255)
                        )
                ))
            },
            {rs ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colorMode" to "primary"
                ))
            },
            { rs ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colorMode" to "full"
                ))
            }
    )

    fun defaultEffectFactories(): List<(RenderScript) -> Effect> {
        return baseEffects
        /* // Inception
        val f = mutableListOf<(RenderScript) -> Effect>()
        f.addAll(baseEffects)
        f.add({rs -> CombinationEffect(rs, baseEffects)})
        f.add({rs -> CombinationEffect(rs, f.subList(1, 10))})
        return f
        */
    }

    fun forNameAndParameters(rs: RenderScript, name: String, params: Map<String, Any>): Effect {
        return when (name) {
            AsciiEffect.EFFECT_NAME -> AsciiEffect.fromParameters(rs, params)
            EdgeEffect.EFFECT_NAME -> EdgeEffect.fromParameters(rs, params)
            EdgeLuminanceEffect.EFFECT_NAME -> EdgeLuminanceEffect.fromParameters(rs, params)
            PermuteColorEffect.EFFECT_NAME -> PermuteColorEffect.fromParameters(rs, params)
            SolidColorEffect.EFFECT_NAME -> SolidColorEffect.fromParameters(rs, params)
            Convolve3x3Effect.EFFECT_NAME -> Convolve3x3Effect.fromParameters(rs, params)
            else -> throw IllegalArgumentException("Unknown effect: " + name)
        }
    }

    fun forMetadata(rs: RenderScript, metadata: EffectMetadata) =
            forNameAndParameters(rs, metadata.name, metadata.parameters)
}