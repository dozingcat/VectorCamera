package com.dozingcatsoftware.boojiecam.effect

import android.renderscript.RenderScript

object EffectRegistry {

    val baseEffects = listOf<(RenderScript, (String, String) -> String) -> Effect>(

            {rs, prefsFn -> EdgeLuminanceEffect(rs) },
            {rs, prefsFn -> PermuteColorEffect.noOp(rs) },
            {rs, prefsFn -> PermuteColorEffect.rgbToBrg(rs) },
            {rs, prefsFn -> PermuteColorEffect.rgbToGbr(rs) },
            {rs, prefsFn -> PermuteColorEffect.flipUV(rs) },

            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 0, 0)
                        )
                ))
            },
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 255, 0)
                        )
                ))
            },
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 0, 255)
                        )
                ))
            },

            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 0)
                        )
                ))
            },
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 255)
                        )
                ))
            },
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "linear_gradient",
                                "minColor" to listOf(0, 0, 0),
                                "gradientStartColor" to listOf(0, 255, 0),
                                "gradientEndColor" to listOf(0, 0, 255)
                        )
                ))
            },
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "radial_gradient",
                                "minColor" to listOf(25, 25, 112),
                                "centerColor" to listOf(255, 255, 0),
                                "outerColor" to listOf(255, 70, 0)
                        )
                ))
            },

            {rs, prefsFn ->
                Convolve3x3Effect.fromParameters(rs, mapOf(
                        "coefficients" to listOf(4, 2, 0, 2, 1, -2, 0, -2, -4),
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
            {rs, prefsFn ->
                Convolve3x3Effect.fromParameters(rs, mapOf(
                        "coefficients" to listOf(4, 2, 0, 2, 1, -2, 0, -2, -4),
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 0, 0)
                        )
                ))
            },

            {rs, prefsFn ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "text" to listOf(255, 255, 255),
                                "background" to listOf(0, 0, 0)
                        ),
                        "pixelChars" to prefsFn("pixelChars.WHITE_ON_BLACK", " .:oO8#"),
                        "prefId" to "pixelChars.WHITE_ON_BLACK"
                ))
            },
            {rs, prefsFn ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "text" to listOf(0, 0, 0),
                                "background" to listOf(255, 255, 255)
                        ),
                        "pixelChars" to prefsFn("pixelChars.BLACK_ON_WHITE", "#o:..  "),
                        "prefId" to "pixelChars.BLACK_ON_WHITE"
                ))
            },
            {rs, prefsFn ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colorMode" to "primary",
                        "pixelChars" to prefsFn("pixelChars.ANSI_COLOR", " .:oO8#"),
                        "prefId" to "pixelChars.ANSI_COLOR"
                ))
            },
            {rs, prefsFn ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colorMode" to "full",
                        "pixelChars" to prefsFn("pixelChars.FULL_COLOR", "O8#"),
                        "prefId" to "pixelChars.FULL_COLOR"
                ))
            },

            // Animated?
            {rs, prefsFn ->
                SolidColorEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "grid_gradient",
                                "minColor" to listOf(0, 0, 0),
                                "grid" to listOf(
                                        listOf(
                                                listOf(255,255,255, 255,0,0, 0,255,0, 0,0,255)
                                        )
                                ),
                                "sizeX" to 0.25,
                                "sizeY" to 0.25,
                                "speedX" to 500,
                                "speedY" to 500
                        )
                ))
            }
    )

    fun defaultEffectFactories(): List<(RenderScript, (String, String) -> String) -> Effect> {
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