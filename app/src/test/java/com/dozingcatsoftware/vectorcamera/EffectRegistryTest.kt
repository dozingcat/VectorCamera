package com.dozingcatsoftware.vectorcamera

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
