package com.dozingcatsoftware.vectorcamera.effect

import android.graphics.Color
import com.dozingcatsoftware.util.jsonStringToMap
import com.dozingcatsoftware.vectorcamera.CustomColorScheme
import com.dozingcatsoftware.vectorcamera.CustomColorSchemeType

enum class EffectContext {
    NORMAL,
    // Rendering a small preview in the effect picker.
    THUMBNAIL,
    PRELOAD,
}

enum class EffectCategory(val displayName: String) {
    EDGES("Edges"),
    GRADIENTS("Gradients"),
    COLORS("Colors"),
    TEXT("Text"),
    ARTISTIC("Artistic"),
    CUSTOM("Custom"),
}

/**
 * Describes an effect that the user can select. `id` is a stable identifier that is safe to
 * store; `name` is shown in the effect picker. `factory` creates the effect using the current
 * preferences (via `prefsFn`) and the context it will be rendered in.
 */
data class EffectInfo(
        val id: String,
        val name: String,
        val category: EffectCategory,
        val factory: ((String, Any) -> Any, EffectContext) -> Effect)

class EffectRegistry {

    // The effects available in the picker, in display order.
    // See Animated2dGradient.kt for description of gradient grids.
    val effectInfos = listOf<EffectInfo>(

            // Edges on black.
            // Edge strength->brightness, preserve colors.
            EffectInfo("edge_luminance", "Color edges", EffectCategory.EDGES) {prefsFn, context -> EdgeLuminanceEffect() },

            // White
            EffectInfo("edge_white", "White edges", EffectCategory.EDGES) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
            // Green
            EffectInfo("edge_green", "Green edges", EffectCategory.EDGES) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(0, 0, 0),
                        "maxColor" to listOf(0, 255, 0)
                    )
                ))
            },
            // Red
            EffectInfo("edge_red", "Red edges", EffectCategory.EDGES) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(0, 0, 0),
                        "maxColor" to listOf(255, 0, 0)
                    )
                ))
            },
            // Cyan
            EffectInfo("edge_cyan", "Cyan edges", EffectCategory.EDGES) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(0, 0, 0),
                        "maxColor" to listOf(0, 255, 255)
                    )
                ))
            },
            // Yellow
            EffectInfo("edge_yellow", "Yellow edges", EffectCategory.EDGES) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 0)
                        )
                ))
            },

            // Edges on light background.
            // Black on white.
            EffectInfo("edge_black_on_white", "Black on white", EffectCategory.EDGES) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(255, 255, 255),
                        "maxColor" to listOf(0, 0, 0)
                    )
                ))
            },
            // Green on white.
            EffectInfo("edge_green_on_white", "Green on white", EffectCategory.EDGES) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(255, 255, 255),
                        "maxColor" to listOf(0, 160, 0)
                    )
                ))
            },
            // Red on white.
            EffectInfo("edge_red_on_white", "Red on white", EffectCategory.EDGES) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(255, 255, 255),
                        "maxColor" to listOf(255, 0, 0)
                    )
                ))
            },
            // Blue on white.
            EffectInfo("edge_blue_on_white", "Blue on white", EffectCategory.EDGES) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(255, 255, 255),
                        "maxColor" to listOf(0, 0, 255)
                    )
                ))
            },

            // Rainbow, animated vertically on white background.
            EffectInfo("edge_rainbow_on_white", "Rainbow on white", EffectCategory.GRADIENTS) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
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

            // Yellow background, 2d gradient colors.
            EffectInfo("edge_gradient_on_yellow", "Gradient on yellow", EffectCategory.GRADIENTS) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
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

            // Gradients.
            // Pink background, 2d gradient colors.
            EffectInfo("edge_gradient_on_pink", "Gradient on pink", EffectCategory.GRADIENTS) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
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
            // Blue-green edges on black.
            EffectInfo("edge_blue_green", "Blue-green edges", EffectCategory.GRADIENTS) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "linear_gradient",
                        "minColor" to listOf(0, 0, 0),
                        "gradientStartColor" to listOf(0, 255, 0),
                        "gradientEndColor" to listOf(0, 0, 255)
                    )
                ))
            },
            // Radial gradient, yellow in center to orange in edges.
            EffectInfo("edge_radial", "Radial glow", EffectCategory.GRADIENTS) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "radial_gradient",
                        "minColor" to listOf(25, 25, 112),
                        "centerColor" to listOf(255, 255, 0),
                        "outerColor" to listOf(255, 70, 0),
                    )
                ))
            },

            // Red-green horizontally animated colors.
            EffectInfo("edge_red_green_animated", "Red-green waves", EffectCategory.GRADIENTS) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
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
            EffectInfo("edge_rainbow_animated", "Sliding rainbow", EffectCategory.GRADIENTS) {prefsFn, context ->
                EdgeEffect.fromParameters(mapOf(
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
                            ),
                        ),
                        "sizeX" to 0.5,
                        "sizeY" to 0.5,
                        "speedX" to 300,
                        "speedY" to 200,
                        "pixelsPerCell" to gradientPixelsPerCell(context)
                    )
                ))
            },
            // Solid rainbow 2d gradient.
            EffectInfo("solid_rainbow", "Rainbow", EffectCategory.GRADIENTS) {prefsFn, context ->
                SolidColorEffect.fromParameters(mapOf(
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

           // Solid color effects.
            EffectInfo("normal", "Normal", EffectCategory.COLORS) {prefsFn, context -> PermuteColorEffect.noOp() },
            // Grayscale negative.
            EffectInfo("grayscale_negative", "Grayscale negative", EffectCategory.COLORS) {prefsFn, context ->
                SolidColorEffect.fromParameters(mapOf(
                    "colors" to mapOf(
                        "type" to "fixed",
                        "minColor" to listOf(255, 255, 255),
                        "maxColor" to listOf(0, 0, 0)
                    )
                ))
            },

            EffectInfo("permute_brg", "Swapped colors RGB->BRG", EffectCategory.COLORS) {prefsFn, context -> PermuteColorEffect.rgbToBrg() },
            EffectInfo("permute_gbr", "Swapped colors RGB->GBR", EffectCategory.COLORS) {prefsFn, context -> PermuteColorEffect.rgbToGbr() },
            EffectInfo("flip_uv", "Complementary colors", EffectCategory.COLORS) {prefsFn, context -> PermuteColorEffect.flipUV() },
            EffectInfo("color_negative", "Color negative", EffectCategory.COLORS) {prefsFn, context -> PermuteColorEffect.colorNegative() },

            // Cyan background, purple/red/yellow foreground.
            EffectInfo("solid_warm_on_cyan", "Warm on cyan", EffectCategory.COLORS) {prefsFn, context ->
                SolidColorEffect.fromParameters(mapOf(
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

            // Text effects.
            // Black text on white background.
            EffectInfo("ascii_black_on_white", "Text black on white", EffectCategory.TEXT) {prefsFn, context ->
                AsciiEffect.fromParameters(mapOf(
                    "colorMode" to "fixed",
                    "colors" to mapOf(
                        "text" to listOf(0, 0, 0),
                        "background" to listOf(255, 255, 255)
                    ),
                    "pixelChars" to asciiChars(prefsFn, "pixelChars.BLACK_ON_WHITE", "#o:..  "),
                    "numColumns" to numAsciiColumns(prefsFn),
                    "prefId" to "pixelChars.BLACK_ON_WHITE"
                ))
            },
            // White text on black background.
            EffectInfo("ascii_white_on_black", "Text white on black", EffectCategory.TEXT) {prefsFn, context ->
                AsciiEffect.fromParameters(mapOf(
                    "colorMode" to "fixed",
                    "colors" to mapOf(
                        "text" to listOf(255, 255, 255),
                        "background" to listOf(0, 0, 0)
                    ),
                    "pixelChars" to asciiChars(prefsFn, "pixelChars.WHITE_ON_BLACK", " .:oO8#"),
                    "numColumns" to numAsciiColumns(prefsFn),
                    "prefId" to "pixelChars.WHITE_ON_BLACK"
                ))
            },
            // ANSI color mode.
            EffectInfo("ascii_primary", "Text primary colors", EffectCategory.TEXT) {prefsFn, context ->
                AsciiEffect.fromParameters(mapOf(
                        "colorMode" to "primary",
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.ANSI_COLOR", " .:oO8#"),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.ANSI_COLOR"
                ))
            },
            // Full color mode.
            EffectInfo("ascii_full_color", "Text full color", EffectCategory.TEXT) {prefsFn, context ->
                AsciiEffect.fromParameters(mapOf(
                        "colorMode" to "full",
                        "pixelChars" to asciiChars(prefsFn, "pixelChars.FULL_COLOR", "O8#"),
                        "numColumns" to numAsciiColumns(prefsFn),
                        "prefId" to "pixelChars.FULL_COLOR"
                ))
            },
            // Matrix with edges.
            EffectInfo("matrix_edges", "Matrix edges", EffectCategory.TEXT) {prefsFn, context ->
                MatrixEffect.fromParameters(mapOf(
                        "numColumns" to numAsciiColumns(prefsFn),
                        "textColor" to matrixTextColor(prefsFn, 0x00ff00),
                        "edges" to true
                ))
            },
            // Solid Matrix.
            EffectInfo("matrix", "Matrix", EffectCategory.TEXT) {prefsFn, context ->
                MatrixEffect.fromParameters(mapOf(
                        "numColumns" to numAsciiColumns(prefsFn),
                        "textColor" to matrixTextColor(prefsFn, 0x00ff00),
                        "edges" to false
                ))
            },
            // Miscellaneous and custom effects.
            // Cartoon
            EffectInfo("cartoon", "Cartoon", EffectCategory.ARTISTIC) {prefsFn, context -> CartoonEffect.fromParameters(mapOf()) },
            // Emboss grayscale
            EffectInfo("emboss", "Emboss", EffectCategory.ARTISTIC) {prefsFn, context ->
                Convolve3x3Effect.fromParameters(mapOf(
                        "coefficients" to listOf(8, 4, 0, 4, 1, -4, 0, -4, -8),
                        "colors" to mapOf(
                                "type" to "fixed",
                                "minColor" to listOf(0, 0, 0),
                                "maxColor" to listOf(255, 255, 255)
                        )
                ))
            },
        
            EffectInfo("oil_painting", "Oil painting", EffectCategory.ARTISTIC) {prefsFn, context -> OilPaintingEffect.standard() },

            EffectInfo("stained_glass", "Stained glass", EffectCategory.ARTISTIC) {prefsFn, context -> StainedGlassEffect.defaultStainedGlass() },

            // Custom edge.
            EffectInfo("custom1", "Custom 1", EffectCategory.CUSTOM) {prefsFn, context ->
                createCustomEffect(prefsFn, context, "custom1",
                        CustomColorScheme(CustomColorSchemeType.EDGE, Color.BLACK,
                                Color.RED, Color.BLUE, Color.GREEN, Color.WHITE))
            },
            // Custom solid.
            EffectInfo("custom2", "Custom 2", EffectCategory.CUSTOM) {prefsFn, context ->
                createCustomEffect(prefsFn, context, "custom2",
                        CustomColorScheme(CustomColorSchemeType.SOLID, Color.BLACK,
                                Color.RED, Color.BLUE, Color.GREEN, Color.WHITE))
            },
    )

    fun defaultEffectCount() = effectInfos.size

    fun defaultEffectAtIndex(index: Int, prefsFn: (String, Any) -> Any,
                             context: EffectContext = EffectContext.NORMAL): Effect {
        return effectInfos[index].factory(prefsFn, context)
    }

    fun effectInfoForId(id: String): EffectInfo? = effectInfos.find {it.id == id}

    fun createEffect(id: String, prefsFn: (String, Any) -> Any,
                     context: EffectContext = EffectContext.NORMAL): Effect {
        val info = effectInfoForId(id) ?: throw IllegalArgumentException("Unknown effect id: $id")
        return info.factory(prefsFn, context)
    }

    fun effectForNameAndParameters(name: String, params: Map<String, Any>): Effect {
        return when (name) {
            AsciiEffect.EFFECT_NAME -> AsciiEffect.fromParameters(params)
            EdgeEffect.EFFECT_NAME -> EdgeEffect.fromParameters(params)
            EdgeLuminanceEffect.EFFECT_NAME -> EdgeLuminanceEffect.fromParameters(params)
            SolidColorEffect.EFFECT_NAME -> SolidColorEffect.fromParameters(params)
            Convolve3x3Effect.EFFECT_NAME -> Convolve3x3Effect.fromParameters(params)
            CartoonEffect.EFFECT_NAME -> CartoonEffect.fromParameters(params)
            MatrixEffect.EFFECT_NAME -> MatrixEffect.fromParameters(params)
            PermuteColorEffect.EFFECT_NAME -> PermuteColorEffect.fromParameters(params)
            OilPaintingEffect.EFFECT_NAME -> OilPaintingEffect.fromParameters(params)
            StainedGlassEffect.EFFECT_NAME -> StainedGlassEffect.fromParameters(params)
            else -> throw IllegalArgumentException("Unknown effect: ${name}")
        }
    }

    fun effectForMetadata(metadata: EffectMetadata) =
            effectForNameAndParameters(metadata.name, metadata.parameters)
}

private fun gradientPixelsPerCell(context: EffectContext): Int {
    return if (context == EffectContext.THUMBNAIL || context == EffectContext.PRELOAD) 20
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

private fun createCustomEffect(
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
        CustomColorSchemeType.EDGE -> EdgeEffect.fromParameters(params)
        CustomColorSchemeType.SOLID -> SolidColorEffect.fromParameters(params)
    }
    return CustomEffect(baseEffect, scheme, customEffectId)
}