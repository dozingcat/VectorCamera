package com.dozingcatsoftware.vectorcamera

import com.dozingcatsoftware.vectorcamera.effect.EffectMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMetadataTest {
    private val effect = EffectMetadata("edge", mapOf("foo" to "bar"))
    private val base = MediaMetadata(
            MediaType.IMAGE, effect, 640, 480, ImageOrientation.NORMAL, 1234L)

    @Test
    fun effectIdRoundTripsThroughJson() {
        val md = base.withEffectMetadata(effect, "edge_red")
        val restored = MediaMetadata.fromJson(md.toJson())
        assertEquals("edge_red", restored.effectId)
        assertEquals("edge", restored.effectMetadata.name)
    }

    @Test
    fun missingEffectIdIsNull() {
        val json = base.toJson()
        assertFalse(json.containsKey("effectId"))
        assertNull(MediaMetadata.fromJson(json).effectId)
    }

    @Test
    fun exportedEffectMetadataPreservesEffectId() {
        val md = base.withEffectMetadata(effect, "edge_red")
                .withExportedEffectMetadata(effect, "png")
        assertEquals("edge_red", md.effectId)
    }
}
