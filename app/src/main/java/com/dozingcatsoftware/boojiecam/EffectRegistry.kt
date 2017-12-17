package com.dozingcatsoftware.boojiecam

import android.renderscript.RenderScript

object EffectRegistry {

    fun defaultEffectFactories(): List<(RenderScript) -> Effect> {
        return listOf(
                {rs -> EdgeLuminanceEffect(rs)},
                {rs -> PermuteColorEffect.noOp(rs)},
                {rs -> PermuteColorEffect.rgbToBrg(rs)},
                {rs -> PermuteColorEffect.rgbToGbr(rs)},
                {rs -> EdgeEffect.fromParameters(rs, mapOf(
                        "type" to "fixed",
                        "minEdgeColor" to listOf(255, 0, 0, 0),
                        "maxEdgeColor" to listOf(255, 255, 255, 255)
                ))},
                {rs -> EdgeEffect.fromParameters(rs, mapOf(
                        "type" to "fixed",
                        "minEdgeColor" to listOf(255, 0, 64, 128),
                        "maxEdgeColor" to listOf(255, 255, 160, 0)
                ))},
                {rs -> EdgeEffect.fromParameters(rs, mapOf(
                        "type" to "linear_gradient",
                        "minEdgeColor" to listOf(255, 0, 0, 0),
                        "gradientStartColor" to listOf(255, 0, 255, 0),
                        "gradientEndColor" to listOf(255, 0, 0, 255)
                ))},
                {rs -> EdgeEffect.fromParameters(rs, mapOf(
                        "type" to "radial_gradient",
                        "minEdgeColor" to listOf(255, 25, 25, 112),
                        "centerColor" to listOf(255, 255, 255, 0),
                        "outerColor" to listOf(255, 255, 70, 0)
                ))},
                {rs -> SolidColorEffect.fromParameters(rs, mapOf(
                        "type" to "fixed",
                        "minEdgeColor" to listOf(255, 255, 255, 255),
                        "maxEdgeColor" to listOf(255, 0, 0, 0)
                ))},
                {rs -> AsciiEffect(rs)}
        )
    }

    fun forNameAndParameters(rs: RenderScript, name: String, params: Map<String, Any>): Effect {
        return when (name) {
            AsciiEffect.EFFECT_NAME -> AsciiEffect.fromParameters(rs, params)
            EdgeEffect.EFFECT_NAME -> EdgeEffect.fromParameters(rs, params)
            EdgeLuminanceEffect.EFFECT_NAME -> EdgeLuminanceEffect.fromParameters(rs, params)
            PermuteColorEffect.EFFECT_NAME -> PermuteColorEffect.fromParameters(rs, params)
            SolidColorEffect.EFFECT_NAME -> SolidColorEffect.fromParameters(rs, params)
            else -> throw IllegalArgumentException("Unknown effect: " + name)
        }
    }
}