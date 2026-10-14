package com.dozingcatsoftware.vectorcamera

import com.dozingcatsoftware.vectorcamera.effect.ColorMapMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomPaletteSchemeTest {
    private val default = CustomPaletteScheme(ColorMapMode.SOLID, listOf(0xFF000000.toInt(), -1))

    @Test
    fun roundTripsThroughMap() {
        val scheme = CustomPaletteScheme(
                ColorMapMode.EDGE, listOf(0xFF102030.toInt(), 0xFF405060.toInt(), -1))
        assertEquals(scheme, CustomPaletteScheme.fromMap(scheme.toMap(), default))
    }

    @Test
    fun tooFewOrInvalidColorsUseDefaults() {
        assertEquals(default.colors,
                CustomPaletteScheme.fromMap(mapOf("colors" to listOf(1)), default).colors)
        assertEquals(default.colors,
                CustomPaletteScheme.fromMap(mapOf("colors" to "nope"), default).colors)
        assertEquals(ColorMapMode.SOLID,
                CustomPaletteScheme.fromMap(mapOf("type" to "bogus"), default).mode)
    }

    @Test
    fun effectParametersSplitColorsIntoRgbStops() {
        val scheme = CustomPaletteScheme(ColorMapMode.SOLID, listOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt()))
        val colors = scheme.toEffectParameters()["colors"] as Map<*, *>
        assertEquals("palette", colors["type"])
        assertEquals(listOf(listOf(0x10, 0x20, 0x30), listOf(255, 255, 255)), colors["stops"])
    }
}
