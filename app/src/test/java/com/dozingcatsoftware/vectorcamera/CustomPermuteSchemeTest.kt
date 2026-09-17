package com.dozingcatsoftware.vectorcamera

import com.dozingcatsoftware.vectorcamera.effect.ColorComponentSource
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomPermuteSchemeTest {
    private val default = CustomPermuteScheme(
            ColorComponentSource.RED, ColorComponentSource.GREEN, ColorComponentSource.BLUE)

    @Test
    fun roundTripsThroughMap() {
        val scheme = CustomPermuteScheme(
                ColorComponentSource.BRIGHTNESS_INVERSE, ColorComponentSource.MAX,
                ColorComponentSource.MIN, flipUV = true)
        assertEquals(scheme, CustomPermuteScheme.fromMap(scheme.toMap(), default))
    }

    @Test
    fun missingAndInvalidValuesUseDefaults() {
        val scheme = CustomPermuteScheme.fromMap(
                mapOf("red" to "BLUE", "green" to "not_a_source"), default)
        assertEquals(ColorComponentSource.BLUE, scheme.redSource)
        assertEquals(ColorComponentSource.GREEN, scheme.greenSource)
        assertEquals(ColorComponentSource.BLUE, scheme.blueSource)
        assertEquals(false, scheme.flipUV)
    }

    @Test
    fun editorSourceListsMatch() {
        assertEquals(EditPermuteSchemeView.SOURCES.size, EditPermuteSchemeView.SOURCE_LABELS.size)
        assertEquals(ColorComponentSource.values().toSet(), EditPermuteSchemeView.SOURCES.toSet())
    }
}
