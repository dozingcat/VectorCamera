package com.dozingcatsoftware.vectorcamera.effect

import android.renderscript.RenderScript

object EffectRegistry {

    // 25 effects, shown in 5x5 grid.
    val baseEffects = listOf<(RenderScript, (String, String) -> String) -> Effect>(

            // Row 1, edges on black.
            // Edge strength->brightness, preserve colors.
            {rs, prefsFn -> EdgeLuminanceEffect(rs) },
            // White
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
            // Green
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 255, 0)
                        )
                ))
            },
            // Red
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 0, 0)
                        )
                ))
            },
            // Cyan
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 255, 255)
                        )
                ))
            },

            // Row 2, edges on non-black.
            // Black on white.
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 0)
                        )
                ))
            },
            // Blue on white.
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 255)
                        )
                ))
            },
            // Red on white.
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(255, 0, 0)
                        )
                ))
            },
            // Yellow background, 2d gradient colors.
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "grid_gradient",
                                "minColor" to listOf(255, 255, 192),
                                "grid" to listOf(
                                        listOf(
                                                listOf(255,0,0, 0,192,0, 0,0,255, 0,0,0)
                                        )
                                )
                        )
                ))
            },
            // Pink background, 2d gradient colors.
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "grid_gradient",
                                "minColor" to listOf(255,182,193),
                                "grid" to listOf(
                                        listOf(
                                                listOf(0,128,0, 128,0,0, 0,128,128, 128,0,128)
                                        )
                                )
                        )
                ))
            },

            // Row 3, animations.
            // Animated colors.
            // Blue-green edges on black.
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
            // Radial gradient background.
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
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "grid_gradient",
                                "minColor" to listOf(0, 0, 0),
                                "grid" to listOf(
                                        listOf(
                                                //listOf(255,255,255, 255,255,255, 255,255,255, 255,255,255)
                                                listOf(255,0,0, 0,255,0, 255,0,0, 0,255,0),
                                                listOf(0,255,0, 255,0,0, 0,255,0, 255,0,0)
                                        )
                                ),
                                "speedX" to 250
                        )
                ))
            },
            // Animated colors with 2d sliding window.
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "grid_gradient",
                                "minColor" to listOf(0, 0, 0),
                                "grid" to listOf(
                                        listOf(
                                                listOf(255,0,0, 0,255,0, 0,0,255, 255,255,255),
                                                listOf(0,255,0, 255,0,0, 255,255,255, 0,0,255)
                                        ),
                                        listOf(
                                                listOf(0,0,255, 255,255,255, 255,0,0, 0,255,0),
                                                listOf(255,255,255, 0,0,255, 0,255,0, 255,0,0)
                                        )
                                ),
                                "sizeX" to 0.5,
                                "sizeY" to 0.5,
                                "speedX" to 300,
                                "speedY" to 200
                        )
                ))
            },
            // Rainbow, white background
            {rs, prefsFn ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "grid_gradient",
                                "minColor" to listOf(255, 255, 255),
                                "grid" to listOf(
                                        listOf(listOf(128,0,0, 128,0,0, 96,96,0, 96,96,0)),
                                        listOf(listOf(96,96,0, 96,96,0, 0,128,0, 0,128,0)),
                                        listOf(listOf(0,128,0, 0,128,0, 0,96,96, 0,96,96)),
                                        listOf(listOf(0,96,96, 0,96,96, 0,0,128, 0,0,128)),
                                        listOf(listOf(0,0,128, 0,0,128, 96,0,96, 96,0,96)),
                                        listOf(listOf(96,0,96, 96,0,96, 128,0,0, 128,0,0))
                                ),
                                "sizeY" to 3.0,
                                "speedY" to 500
                        )
                ))
            },

            // Row 4.
            // Solid effects
            {rs, prefsFn ->
                SolidColorEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "grid_gradient",
                                "minColor" to listOf(0, 0, 0),
                                "grid" to listOf(
                                        listOf(
                                                listOf(255,255,255, 255,0,0, 0,255,0, 0,0,255)
                                        )
                                )
                        )
                ))
            },
            {rs, prefsFn -> PermuteColorEffect.rgbToBrg(rs) },
            {rs, prefsFn -> PermuteColorEffect.rgbToGbr(rs) },
            {rs, prefsFn -> PermuteColorEffect.flipUV(rs) },
            // Emboss grayscale.
            {rs, prefsFn ->
                Convolve3x3Effect.fromParameters(rs, mapOf(
                        "coefficients" to listOf(8, 4, 0, 4, 1, -4, 0, -4, -8),
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },

            // Row 5.
            {rs, prefsFn ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "text" to listOf(255, 255, 255),
                                "background" to listOf(0, 0, 0)
                        ),
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.WHITE_ON_BLACK", " .:oO8#"),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.WHITE_ON_BLACK"
                ))
            },
            {rs, prefsFn ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "text" to listOf(0, 0, 0),
                                "background" to listOf(255, 255, 255)
                        ),
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.BLACK_ON_WHITE", "#o:..  "),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.BLACK_ON_WHITE"
                ))
            },
            {rs, prefsFn ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colorMode" to "primary",
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.ANSI_COLOR", " .:oO8#"),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.ANSI_COLOR"
                ))
            },
            {rs, prefsFn ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colorMode" to "full",
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.FULL_COLOR", "O8#"),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.FULL_COLOR"
                ))
            },
            {rs, prefsFn -> PermuteColorEffect.noOp(rs) }



    )

    fun defaultEffectFactories(): List<(RenderScript, (String, String) -> String) -> Effect> {
        return baseEffects
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

private fun numAsciiColumns(prefsFn: (String, String) -> String): Int {
    val prefsVal = prefsFn("numAsciiColumns", "")
    if (!prefsVal.isEmpty()) {
        try {
            return Integer.parseInt(prefsVal)
        }
        catch (ex: NumberFormatException) {}
    }
    return AsciiEffect.DEFAULT_CHARACTER_COLUMNS
}

private fun asciiChars(
        prefsFn: (String, String) -> String, prefId: String, defValue: String): String {
    val prefsVal = prefsFn(prefId, defValue)
    if (prefsVal.isEmpty()) {
        return defValue
    }
    return prefsVal
}