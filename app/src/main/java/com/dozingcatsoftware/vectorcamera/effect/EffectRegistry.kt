package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Color
import android.renderscript.RenderScript
import com.dozingcatsoftware.util.jsonStringToMap
import com.dozingcatsoftware.vectorcamera.CustomColorScheme
import com.dozingcatsoftware.vectorcamera.CustomColorSchemeType
import com.dozingcatsoftware.vectorcamera.effect.EdgeEffectKotlin

enum class EffectContext {
    NORMAL,
    COMBO_GRID,
    PRELOAD,
}

class EffectRegistry {

    // 36 effects, shown in 6x6 grid.
    // See Animated2dGradient.kt for description of gradient grids.
    val baseEffects = listOf<(RenderScript, (String, Any) -> Any, EffectContext) -> Effect>(

            // Row 1, edges on black.
            // Edge strength->brightness, preserve colors.
            {rs, prefsFn, context -> EdgeLuminanceEffect(rs) },
            {rs, prefsFn, context -> EdgeLuminanceEffectKotlin() },

            // Edge strength->color map (Kotlin implementation)
            {rs, prefsFn, context -> EdgeEffectKotlin.fromParameters(mapOf(
                    "colors" to mapOf(
                            "type" to "fixed",
                            "minColor" to listOf(0, 0, 0),
                            "maxColor" to listOf(255, 255, 255)
                    )
            )) },

            // White
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
            // Green
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 255, 0)
                        )
                ))
            },
            // Red
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 0, 0)
                        )
                ))
            },
            // Blue
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 0, 255)
                        )
                ))
            },
            // Cyan
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(0, 255, 255)
                        )
                ))
            },

            // Row 2
            // Purple
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 0, 255)
                        )
                ))
            },
            // Yellow
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 0)
                        )
                ))
            },

            // Black on white.
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 0)
                        )
                ))
            },
            // Green on white.
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 160, 0)
                        )
                ))
            },
            // Red on white.
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(255, 0, 0)
                        )
                ))
            },
            // Blue on white.
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 255)
                        )
                ))
            },

            // Row 3
            // Yellow background, 2d gradient colors.
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
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
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
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
            {rs, prefsFn, context ->
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
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "radial_gradient",
                                "minColor" to listOf(25, 25, 112),
                                "centerColor" to listOf(255, 255, 0),
                                "outerColor" to listOf(255, 70, 0)
                        )
                ))
            },
            // Red-green horizontally animated colors.
            {rs, prefsFn, context ->
                EdgeEffect.fromParameters(rs, mapOf(
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
            {rs, prefsFn, context ->
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
                                "speedY" to 200,
                                "pixelsPerCell" to gradientPixelsPerCell(context)
                        )
                ))
            },

            // Row 4
            // Rainbow, animated vertically on white background.
            {rs, prefsFn, context ->
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
                                "speedY" to 500,
                                "pixelsPerCell" to gradientPixelsPerCell(context)
                        )
                ))
            },

            // Solid effects
            // Rainbow 2d gradient.
            {rs, prefsFn, context ->
                SolidColorEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "grid_gradient",
                                "minColor" to listOf(0, 0, 0),
                                "grid" to listOf(
                                        listOf(
                                                listOf(255,255,255, 255,0,0, 0,255,0, 0,0,255)
                                        )
                                ),
                                "pixelsPerCell" to gradientPixelsPerCell(context)
                        )
                ))
            },
            // Cyan background, purple/red/yellow foreground.
            {rs, prefsFn, context ->
                SolidColorEffect.fromParameters(rs, mapOf(
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

            {rs, prefsFn, context -> PermuteColorEffect.rgbToBrg(rs) },
            {rs, prefsFn, context -> PermuteColorEffect.rgbToGbr(rs) },
            {rs, prefsFn, context -> PermuteColorEffect.flipUV(rs) },

            // Row 5. Text effects.
            {rs, prefsFn, context ->
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
            {rs, prefsFn, context ->
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
            {rs, prefsFn, context ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colorMode" to "primary",
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.ANSI_COLOR", " .:oO8#"),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.ANSI_COLOR"
                ))
            },
            {rs, prefsFn, context ->
                AsciiEffect.fromParameters(rs, mapOf(
                        "colorMode" to "full",
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.FULL_COLOR", "O8#"),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.FULL_COLOR"
                ))
            },
            {rs, prefsFn, context ->
                MatrixEffect.fromParameters(rs, mapOf(
                        "numColumns" to numAsciiColumns(prefsFn),
                        "textColor" to matrixTextColor(prefsFn, 0x00ff00),
                        "edges" to true
                ))
            },
            {rs, prefsFn, context ->
                MatrixEffect.fromParameters(rs, mapOf(
                        "numColumns" to numAsciiColumns(prefsFn),
                        "textColor" to matrixTextColor(prefsFn, 0x00ff00),
                        "edges" to false
                ))
            },

            // Row 6
            {rs, prefsFn, context -> PermuteColorEffect.noOp(rs) },

            // Grayscale negative.
            {rs, prefsFn, context ->
                SolidColorEffect.fromParameters(rs, mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(255, 255, 255),
                                "maxColor" to listOf(0, 0, 0)
                        )
                ))
            },

            {rs, prefsFn, context -> CartoonEffect.fromParameters(rs, mapOf()) },

            // Emboss grayscale.
            {rs, prefsFn, context ->
                Convolve3x3Effect.fromParameters(rs, mapOf(
                        "coefficients" to listOf(8, 4, 0, 4, 1, -4, 0, -4, -8),
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },

            // Custom edge.
            {rs, prefsFn, context ->
                createCustomEffect(rs, prefsFn, context, "custom1",
                        CustomColorScheme(CustomColorSchemeType.EDGE, Color.BLACK,
                                Color.RED, Color.BLUE, Color.GREEN, Color.WHITE))
            },

            // Custom solid.
            {rs, prefsFn, context ->
                createCustomEffect(rs, prefsFn, context, "custom2",
                        CustomColorScheme(CustomColorSchemeType.SOLID, Color.BLACK,
                                Color.RED, Color.BLUE, Color.GREEN, Color.WHITE))
            }

    // TODO: Customizable edge/solid effects.
    )

    fun defaultEffectCount() = baseEffects.size

    // The smallest N such that a N*N grid can show all the effects, e.g. 5 for 25.
    fun gridSizeForDefaultEffects() = Math.ceil(Math.sqrt(defaultEffectCount().toDouble())).toInt()

    fun defaultEffectAtIndex(index: Int, rs: RenderScript, prefsFn: (String, Any) -> Any,
                             context: EffectContext = EffectContext.NORMAL): Effect {
        return baseEffects[index](rs, prefsFn, context)
    }

    fun defaultEffectFunctions(rs: RenderScript, prefsFn: (String, Any) -> Any,
                               context: EffectContext = EffectContext.NORMAL): List<() -> Effect> {
        val fns = mutableListOf<() -> Effect>()
        for (i in 0 until defaultEffectCount()) {
            fns.add({defaultEffectAtIndex(i, rs, prefsFn, context)})
        }
        return fns
    }

    fun effectForNameAndParameters(
            rs: RenderScript, name: String, params: Map<String, Any>): Effect {
        return when (name) {
            AsciiEffect.EFFECT_NAME -> AsciiEffect.fromParameters(rs, params)
            EdgeEffect.EFFECT_NAME -> EdgeEffect.fromParameters(rs, params)
            EdgeEffectKotlin.EFFECT_NAME -> EdgeEffectKotlin.fromParameters(params)
            EdgeLuminanceEffect.EFFECT_NAME -> EdgeLuminanceEffect.fromParameters(rs, params)
            EdgeLuminanceEffectKotlin.EFFECT_NAME -> EdgeLuminanceEffectKotlin.fromParameters(params)
            PermuteColorEffect.EFFECT_NAME -> PermuteColorEffect.fromParameters(rs, params)
            SolidColorEffect.EFFECT_NAME -> SolidColorEffect.fromParameters(rs, params)
            Convolve3x3Effect.EFFECT_NAME -> Convolve3x3Effect.fromParameters(rs, params)
            CartoonEffect.EFFECT_NAME -> CartoonEffect.fromParameters(rs, params)
            MatrixEffect.EFFECT_NAME -> MatrixEffect.fromParameters(rs, params)
            else -> throw IllegalArgumentException("Unknown effect: ${name}")
        }
    }

    fun effectForMetadata(rs: RenderScript, metadata: EffectMetadata) =
            effectForNameAndParameters(rs, metadata.name, metadata.parameters)
}

private fun gradientPixelsPerCell(context: EffectContext): Int {
    return if (context == EffectContext.COMBO_GRID || context == EffectContext.PRELOAD) 20
    else Animated2dGradient.DEFAULT_PIXELS_PER_CELL
}

private fun numAsciiColumns(prefsFn: (String, Any) -> Any): Int {
    val prefsVal = prefsFn("numAsciiColumns", "") as String
    if (!prefsVal.isEmpty()) {
        try {
            return Integer.parseInt(prefsVal)
        }
        catch (ex: NumberFormatException) {}
    }
    return AsciiEffect.DEFAULT_CHARACTER_COLUMNS
}

private fun asciiChars(
        prefsFn: (String, Any) -> Any, prefId: String, defValue: String): String {
    val prefsVal = prefsFn(prefId, defValue) as String
    if (prefsVal.isEmpty()) {
        return defValue
    }
    return prefsVal
}

private fun matrixTextColor(prefsFn: (String, Any) -> Any, defValue: Int): Int {
    return prefsFn("matrixTextColor", defValue) as Int
}

private fun rgbComponents(vararg colors: Int): List<Int> {
    val rgbList = mutableListOf<Int>()
    for (c in colors) {
        rgbList.add(Color.red(c))
        rgbList.add(Color.green(c))
        rgbList.add(Color.blue(c))
    }
    return rgbList
}

private fun createCustomEffect(
        rs: RenderScript,
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
        CustomColorSchemeType.EDGE -> EdgeEffect.fromParameters(rs, params)
        CustomColorSchemeType.SOLID -> SolidColorEffect.fromParameters(rs, params)
    }
    return CustomEffect(baseEffect, ctx, scheme, customEffectId)
}
