package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Color
import com.dozingcatsoftware.util.jsonStringToMap
import com.dozingcatsoftware.vectorcamera.CustomColorScheme
import com.dozingcatsoftware.vectorcamera.CustomColorSchemeType

enum class EffectContext {
    NORMAL,
    COMBO_GRID,
    PRELOAD,
}

class EffectRegistry {

    // 36 effects, shown in 6x6 grid.
    // See Animated2dGradient.kt for description of gradient grids.
    val baseEffects = listOf<((String, Any) -> Any, EffectContext) -> Effect>(

            // Row 1, edges on black.
            // Edge strength->brightness, preserve colors.
            {prefsFn, context -> EdgeLuminanceEffectKotlin() },

            // White
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
            // Green
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(0, 0, 0),
                        "maxColor" to listOf(0, 255, 0)
                    )
                ))
            },
            // Red
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(0, 0, 0),
                        "maxColor" to listOf(255, 0, 0)
                    )
                ))
            },
            // Blue
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 0, 255)
                        )
                ))
            },
            // Cyan
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(0, 0, 0),
                        "maxColor" to listOf(0, 255, 255)
                    )
                ))
            },
            // Row 2
            // Purple
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(0, 0, 0),
                        "maxColor" to listOf(255, 0, 255)
                    )
                ))
            },
            // Yellow
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 0)
                        )
                ))
            },
            // Black on white.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(255, 255, 255),
                        "maxColor" to listOf(0, 0, 0)
                    )
                ))
            },
            // Green on white.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(255, 255, 255),
                        "maxColor" to listOf(0, 160, 0)
                    )
                ))
            },
            // Red on white.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(255, 255, 255),
                        "maxColor" to listOf(255, 0, 0)
                    )
                ))
            },
            // Blue on white.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(255, 255, 255),
                        "maxColor" to listOf(0, 0, 255)
                    )
                ))
            },
            // Row 3
            // Yellow background, 2d gradient colors.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "grid_gradient",
                        "minColor" to listOf(255, 255, 192),
                        "grid" to listOf(
                            listOf(
                                listOf(255,0,0, 0,192,0, 0,0,255, 0,0,0)
                            )
                        ),
                        "pixelsPerCell" to gradientPixelsPerCell(context)
                    )
                ))
            },
            // Pink background, 2d gradient colors.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "grid_gradient",
                        "minColor" to listOf(255,182,193),
                        "grid" to listOf(
                            listOf(
                                listOf(0,128,0, 128,0,0, 0,128,128, 128,0,128)
                            )
                        ),
                        "pixelsPerCell" to gradientPixelsPerCell(context)
                    )
                ))
            },
            // Animated colors.
            // Blue-green edges on black.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "linear_gradient",
                        "minColor" to listOf(0, 0, 0),
                        "gradientStartColor" to listOf(0, 255, 0),
                        "gradientEndColor" to listOf(0, 0, 255)
                    )
                ))
            },
            // Radial gradient background.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "radial_gradient",
                        "minColor" to listOf(25, 25, 112),
                        "centerColor" to listOf(255, 255, 0),
                        "outerColor" to listOf(255, 70, 0),
                    )
                ))
            },

            // Red-green horizontally animated colors.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                        "colors" to mapOf(
                                "type" to "grid_gradient",
                                "minColor" to listOf(0, 0, 0),
                                "grid" to listOf(
                                        listOf(
                                                listOf(255,0,0, 0,255,0, 255,0,0, 0,255,0),
                                                listOf(0,255,0, 255,0,0, 0,255,0, 255,0,0)
                                        )
                                ),
                                "speedX" to 250,
                                "pixelsPerCell" to gradientPixelsPerCell(context)
                        )
                ))
            },
            // Animated colors with 2d sliding window.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "grid_gradient",
                        "minColor" to listOf(0, 0, 0),
                        "grid" to listOf(
                            listOf(
                                listOf(255,0,0, 0,255,0, 0,0,255, 255,255,255), 
                                listOf(0,255,0, 255,0,0, 255,255,255, 0,0,255)
                            )
                        ),
                        "sizeX" to 0.5,
                        "sizeY" to 0.5,
                        "speedX" to 300,
                        "speedY" to 200,
                        "pixelsPerCell" to gradientPixelsPerCell(context)
                    )
                ))
            },
            // Row 4
            // Rainbow, animated vertically on white background.
            {prefsFn, context ->
                EdgeEffectKotlin.fromParameters(mapOf(
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
                        "speedY" to 500,
                        "pixelsPerCell" to gradientPixelsPerCell(context)
                    )
                ))
            },
            // Solid effects
            // Rainbow 2d gradient.
            {prefsFn, context ->
                SolidColorEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "grid_gradient",
                        "minColor" to listOf(0, 0, 0),
                        "grid" to listOf(
                            listOf(listOf(255,255,255, 255,0,0, 0,255,0, 0,0,255))
                        ),
                        "pixelsPerCell" to gradientPixelsPerCell(context)
                    )
                ))
            },
            // Cyan background, purple/red/yellow foreground.
            {prefsFn, context ->
                SolidColorEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "grid_gradient",
                        "minColor" to listOf(0, 255, 255),
                        "grid" to listOf(
                            listOf(
                                listOf(255,0,255, 255,0,0, 255,0,255, 255,0,0),
                                listOf(255,0,0, 255,128,0, 255,0,0, 255,128,0),
                                listOf(255,128,0, 255,0,0, 255,128,0, 255,0,0),
                                listOf(255,0,0, 255,0,255, 255,0,0, 255,0,255)
                            )
                        ),
                        "speedX" to 500,
                        "pixelsPerCell" to gradientPixelsPerCell(context)
                    )
                ))
            },

            {prefsFn, context -> PermuteColorEffectKotlin.rgbToBrg() },
            {prefsFn, context -> PermuteColorEffectKotlin.rgbToGbr() },
            {prefsFn, context -> PermuteColorEffectKotlin.flipUV() },

            // Row 5. Text effects.
            // White text on black background.
            {prefsFn, context ->
                AsciiEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "text" to listOf(255, 255, 255),
                        "background" to listOf(0, 0, 0)
                    ),
                    "pixelChars" to asciiChars(prefsFn, "pixelChars.WHITE_ON_BLACK", " .:oO8#"),
                    "numColumns" to numAsciiColumns(prefsFn),
                    "prefId" to "pixelChars.WHITE_ON_BLACK"
                ))
            },
            // Black text on white background.
            {prefsFn, context ->
                AsciiEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                        "text" to listOf(0, 0, 0),
                        "background" to listOf(255, 255, 255)
                    ),
                    "pixelChars" to asciiChars(prefsFn, "pixelChars.BLACK_ON_WHITE", "#o:..  "),
                    "numColumns" to numAsciiColumns(prefsFn),
                    "prefId" to "pixelChars.BLACK_ON_WHITE"
                ))
            },
            // ANSI color mode.
            {prefsFn, context ->
                AsciiEffectKotlin.fromParameters(mapOf(
                        "colorMode" to "primary",
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.ANSI_COLOR", " .:oO8#"),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.ANSI_COLOR"
                ))
            },
            // Full color mode.
            {prefsFn, context ->
                AsciiEffectKotlin.fromParameters(mapOf(
                        "colorMode" to "full",
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.FULL_COLOR", "O8#"),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.FULL_COLOR"
                ))
            },
            // Matrix with edges.
            {prefsFn, context ->
                MatrixEffectKotlin.fromParameters(mapOf(
                        "numColumns" to numAsciiColumns(prefsFn),
                        "textColor" to matrixTextColor(prefsFn, 0x00ff00),
                        "edges" to true
                ))
            },
            // Solid Matrix.
            {prefsFn, context ->
                MatrixEffectKotlin.fromParameters(mapOf(
                        "numColumns" to numAsciiColumns(prefsFn),
                        "textColor" to matrixTextColor(prefsFn, 0x00ff00),
                        "edges" to false
                ))
            },
            // Row 6
            {prefsFn, context -> PermuteColorEffectKotlin.noOp() },
            // Grayscale negative.
            {prefsFn, context ->
                SolidColorEffectKotlin.fromParameters(mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 0)
                        )
                ))
            },
            // Cartoon effect.
            {prefsFn, context -> CartoonEffectKotlin.fromParameters(mapOf()) },
            // Emboss grayscale.
            {prefsFn, context ->
                Convolve3x3EffectKotlin.fromParameters(mapOf(
                        "coefficients" to listOf(8, 4, 0, 4, 1, -4, 0, -4, -8),
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
            // Custom edge.
            {prefsFn, context ->
                createCustomEffectKotlin(prefsFn, context, "custom1",
                        CustomColorScheme(CustomColorSchemeType.EDGE, Color.BLACK,
                                Color.RED, Color.BLUE, Color.GREEN, Color.WHITE))
            },
            // Custom solid.
            {prefsFn, context ->
                createCustomEffectKotlin(prefsFn, context, "custom2",
                        CustomColorScheme(CustomColorSchemeType.SOLID, Color.BLACK,
                                Color.RED, Color.BLUE, Color.GREEN, Color.WHITE))
            },

    // TODO: Customizable edge/solid effects.
    )

    fun defaultEffectCount() = baseEffects.size

    // The smallest N such that a N*N grid can show all the effects, e.g. 5 for 25.
    fun gridSizeForDefaultEffects() = Math.ceil(Math.sqrt(defaultEffectCount().toDouble())).toInt()

    fun defaultEffectAtIndex(index: Int, prefsFn: (String, Any) -> Any,
                             context: EffectContext = EffectContext.NORMAL): Effect {
        return baseEffects[index](prefsFn, context)
    }

    fun defaultEffectFunctions(prefsFn: (String, Any) -> Any,
                               context: EffectContext = EffectContext.NORMAL): List<() -> Effect> {
        val fns = mutableListOf<() -> Effect>()
        for (i in 0 until defaultEffectCount()) {
            fns.add({defaultEffectAtIndex(i, prefsFn, context)})
        }
        return fns
    }

    fun effectForNameAndParameters(name: String, params: Map<String, Any>): Effect {
        return when (name) {
            AsciiEffectKotlin.EFFECT_NAME -> AsciiEffectKotlin.fromParameters(params)
            EdgeEffectKotlin.EFFECT_NAME -> EdgeEffectKotlin.fromParameters(params)
            EdgeLuminanceEffectKotlin.EFFECT_NAME -> EdgeLuminanceEffectKotlin.fromParameters(params)
            SolidColorEffectKotlin.EFFECT_NAME -> SolidColorEffectKotlin.fromParameters(params)
            Convolve3x3EffectKotlin.EFFECT_NAME -> Convolve3x3EffectKotlin.fromParameters(params)
            CartoonEffectKotlin.EFFECT_NAME -> CartoonEffectKotlin.fromParameters(params)
            MatrixEffectKotlin.EFFECT_NAME -> MatrixEffectKotlin.fromParameters(params)
            PermuteColorEffectKotlin.EFFECT_NAME -> PermuteColorEffectKotlin.fromParameters(params)
            else -> throw IllegalArgumentException("Unknown effect: ${name}")
        }
    }

    fun effectForMetadata(metadata: EffectMetadata) =
            effectForNameAndParameters(metadata.name, metadata.parameters)
}

private fun gradientPixelsPerCell(context: EffectContext): Int {
    return if (context == EffectContext.COMBO_GRID || context == EffectContext.PRELOAD) 20
    else Animated2dGradient.DEFAULT_PIXELS_PER_CELL
}

private fun asciiChars(prefsFn: (String, Any) -> Any, prefId: String, default: String): String {
    return prefsFn(prefId, default) as String
}

private fun numAsciiColumns(prefsFn: (String, Any) -> Any): Int {
    return prefsFn("numColumns", 120) as Int
}

private fun matrixTextColor(prefsFn: (String, Any) -> Any, default: Int): Int {
    return prefsFn("matrixTextColor", default) as Int
}

private fun rgbComponents(vararg colors: Int): List<Int> {
    val result = mutableListOf<Int>()
    for (color in colors) {
        result.add(Color.red(color))
        result.add(Color.green(color))
        result.add(Color.blue(color))
    }
    return result
}

private fun createCustomEffectKotlin(
    prefsFn: (String, Any) -> Any,
    ctx: EffectContext,
    customEffectId: String,
    defaultScheme: CustomColorScheme): Effect {
    val schemeJson =
        try {jsonStringToMap(prefsFn(customEffectId, "{}") as String)}
        catch (ex: Exception) {mapOf<String, Any>()}
    val scheme = CustomColorScheme.fromMap(schemeJson, defaultScheme)
    // Construct a gradient grid from the CustomColorScheme colors.
    val params = mapOf(
        "type" to "grid_gradient",
        "minColor" to rgbComponents(scheme.backgroundColor),
        "grid" to listOf(listOf(rgbComponents(
            scheme.topLeftColor, scheme.topRightColor,
            scheme.bottomLeftColor, scheme.bottomRightColor))),
        "pixelsPerCell" to gradientPixelsPerCell(ctx)
    )
    val baseEffect = when (scheme.type) {
        CustomColorSchemeType.EDGE -> EdgeEffectKotlin.fromParameters(params)
        CustomColorSchemeType.SOLID -> SolidColorEffectKotlin.fromParameters(params)
    }
    return CustomEffect(baseEffect, ctx, scheme, customEffectId)
}