package com.dozingcatsoftware.vectorcamera

import com.dozingcatsoftware.vectorcamera.effect.EffectMetadata
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectRegistryTest {
    private val registry = EffectRegistry()

    @Test
    fun effectIdsAreUnique() {
        val ids = registry.effectInfos.map {it.id}
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun effectIdsAndNamesAreNonEmpty() {
        for (info in registry.effectInfos) {
            assertTrue("Empty id for ${info.name}", info.id.isNotEmpty())
            assertTrue("Empty name for ${info.id}", info.name.isNotEmpty())
        }
    }

    @Test
    fun effectInfoLookupById() {
        assertNotNull(registry.effectInfoForId("custom1"))
        assertNull(registry.effectInfoForId("no_such_effect"))
        assertEquals(registry.effectInfos.size, registry.defaultEffectCount())
    }
}

class EffectMetadataFallbackTest {
    private val registry = EffectRegistry()

    @Test
    fun unknownEffectNameFallsBackInsteadOfThrowing() {
        val effect = registry.effectForMetadata(EffectMetadata("no_such_effect", mapOf()))
        assertEquals("edge_luminance", effect.effectName())
    }

    @Test
    fun legacyEdgeAndSolidNamesLoadAsColorMap() {
        val params = mapOf("colors" to mapOf(
                "type" to "fixed", "minColor" to listOf(0, 0, 0), "maxColor" to listOf(255, 255, 255)))
        val edge = registry.effectForNameAndParameters("edge", params)
        assertEquals("color_map", edge.effectName())
        assertEquals("edge", edge.effectParameters()["mode"])
        val solid = registry.effectForNameAndParameters("solid_color", params)
        assertEquals("color_map", solid.effectName())
        assertEquals("solid", solid.effectParameters()["mode"])
    }
}
