package com.dozingcatsoftware.vectorcamera

import com.dozingcatsoftware.vectorcamera.effect.ContourEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ContourEffectTest {
    @Test
    fun levelsQuantizeBrightness() {
        val y = byteArrayOf(0, 31, 32, 127, 128.toByte(), 255.toByte())
        val levels = ContourEffect.computeLevels(y, 6, 1, 8, 0)
        assertArrayEquals(byteArrayOf(0, 0, 1, 3, 4, 7), levels)
    }

    @Test
    fun boxBlurOfConstantImageIsUnchanged() {
        val data = ByteArray(5 * 4) {200.toByte()}
        assertArrayEquals(data, ContourEffect.boxBlur(data, 5, 4, 2))
    }

    @Test
    fun boxBlurAveragesNeighbors() {
        // 3 wide, 1 tall: radius 1 averages each pixel with its clamped neighbors.
        val data = byteArrayOf(0, 90, 0)
        val blurred = ContourEffect.boxBlur(data, 3, 1, 1)
        assertArrayEquals(byteArrayOf(30, 30, 30), blurred)
    }

    @Test
    fun linesAppearAtLevelBoundaries() {
        // Two levels side by side in a 4x2 image.
        val levels = byteArrayOf(0, 0, 1, 1, 0, 0, 1, 1)
        val fill = intArrayOf(10, 20)
        val pixels = IntArray(8)
        ContourEffect.renderLevels(levels, 4, 2, 0, 2, fill, 99, 1, pixels)
        // Thickness 1: only the pixel to the right of the boundary is a line.
        assertArrayEquals(intArrayOf(10, 10, 99, 20, 10, 10, 99, 20), pixels)
        ContourEffect.renderLevels(levels, 4, 2, 0, 2, fill, 99, 2, pixels)
        // Thickness 2: both sides of the boundary.
        assertArrayEquals(intArrayOf(10, 99, 99, 20, 10, 99, 99, 20), pixels)
    }

    @Test
    fun renderRespectsRowRange() {
        val levels = ByteArray(4 * 3)
        val pixels = IntArray(12) {-1}
        ContourEffect.renderLevels(levels, 4, 3, 1, 2, intArrayOf(7), 0, 2, pixels)
        assertEquals(-1, pixels[0])
        assertEquals(7, pixels[4])
        assertEquals(-1, pixels[8])
    }
}
