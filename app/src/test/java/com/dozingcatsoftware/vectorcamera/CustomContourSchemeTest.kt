package com.dozingcatsoftware.vectorcamera

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomContourSchemeTest {
    private val default = CustomContourScheme(listOf(0xFF000000.toInt(), -1), 0xFF808080.toInt())

    @Test
    fun roundTripsThroughMap() {
        val scheme = CustomContourScheme(
                listOf(0xFF102030.toInt(), 0xFF405060.toInt(), -1), 0xFF00FF00.toInt())
        assertEquals(scheme, CustomContourScheme.fromMap(scheme.toMap(), default))
    }

    @Test
    fun invalidValuesUseDefaults() {
        assertEquals(default, CustomContourScheme.fromMap(mapOf(), default))
        assertEquals(default.fillColors,
                CustomContourScheme.fromMap(mapOf("fillColors" to listOf(1)), default).fillColors)
        assertEquals(default.lineColor,
                CustomContourScheme.fromMap(mapOf("lineColor" to "nope"), default).lineColor)
    }

    @Test
    fun effectParametersSplitColorsIntoRgb() {
        val scheme = CustomContourScheme(listOf(0xFF102030.toInt(), -1), 0xFF0A0B0C.toInt())
        val colors = scheme.toEffectParameters()["colors"] as Map<*, *>
        assertEquals(listOf(listOf(0x10, 0x20, 0x30), listOf(255, 255, 255)), colors["fill"])
        assertEquals(listOf(0x0A, 0x0B, 0x0C), colors["line"])
    }
}
