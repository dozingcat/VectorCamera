package com.dozingcatsoftware.vectorcamera.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Tests for the Kotlin implementation of progressive Poisson disk sampling in StippleEffect.
 */
class StippleEffectTest {
    private val width = 400
    private val height = 300
    private val minSpacing = 4f
    private val numLayers = 6

    private class Pt(val x: Float, val y: Float, val rank: Float)

    private fun generate(seed: Int = 0): List<Pt> {
        val floats = StippleEffect.generatePoissonPointsKotlin(
                width, height, minSpacing, numLayers, seed)
        assertEquals(0, floats.size % 3)
        return (0 until floats.size / 3).map { Pt(floats[3 * it], floats[3 * it + 1], floats[3 * it + 2]) }
    }

    /** Smallest distance between any two points, via a grid so it's fast. */
    private fun minPairwiseDistance(points: List<Pt>, cell: Float): Float {
        val cols = (width / cell).toInt() + 1
        val rows = (height / cell).toInt() + 1
        val buckets = Array(cols * rows) { mutableListOf<Pt>() }
        for (p in points) {
            buckets[(p.y / cell).toInt() * cols + (p.x / cell).toInt()].add(p)
        }
        var best = Float.MAX_VALUE
        for (p in points) {
            val cx = (p.x / cell).toInt()
            val cy = (p.y / cell).toInt()
            for (gy in maxOf(0, cy - 1)..minOf(rows - 1, cy + 1)) {
                for (gx in maxOf(0, cx - 1)..minOf(cols - 1, cx + 1)) {
                    for (q in buckets[gy * cols + gx]) {
                        if (q === p) continue
                        val d = sqrt((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y))
                        if (d < best) best = d
                    }
                }
            }
        }
        return best
    }

    @Test
    fun pointsAreInBoundsWithValidRanks() {
        val points = generate()
        // Poisson disk sampling gives roughly 0.65 points per spacing^2.
        val expected = width * height / (minSpacing * minSpacing) * 0.65
        assertTrue("Expected about $expected points, got ${points.size}",
                points.size > expected * 0.8 && points.size < expected * 1.2)
        for (p in points) {
            assertTrue(p.x >= 0f && p.x < width)
            assertTrue(p.y >= 0f && p.y < height)
            assertTrue(p.rank > 0f && p.rank <= 1f)
        }
    }

    @Test
    fun fullSetRespectsMinimumSpacing() {
        val points = generate()
        val minDist = minPairwiseDistance(points, minSpacing)
        assertTrue("Min distance $minDist < $minSpacing", minDist >= minSpacing)
    }

    @Test
    fun rankPrefixesRespectLayerSpacing() {
        // The points with rank <= 2^(layer - (numLayers-1)) are exactly the layers up to `layer`,
        // whose spacing is minSpacing * 2^((numLayers-1-layer)/2).
        val points = generate()
        for (layer in 0 until numLayers) {
            val maxRank = 2.0.pow(layer - (numLayers - 1)).toFloat()
            val spacing = minSpacing * 2.0.pow((numLayers - 1 - layer) * 0.5).toFloat()
            val subset = points.filter { it.rank <= maxRank }
            assertTrue(subset.size > 10)
            val minDist = minPairwiseDistance(subset, spacing)
            // Allow for float rounding.
            assertTrue("Layer $layer: min distance $minDist < spacing $spacing",
                    minDist >= spacing * 0.999f)
        }
    }

    @Test
    fun densityIsLinearInRank() {
        val points = generate()
        for (t in listOf(0.1f, 0.25f, 0.5f, 0.75f)) {
            val fraction = points.count { it.rank <= t }.toFloat() / points.size
            assertEquals("Fraction of points with rank <= $t", t, fraction, 0.03f)
        }
    }

    @Test
    fun coversTheWholeImage() {
        // Every 32x32 block should contain points from the full set.
        val points = generate()
        val block = 32
        val cols = width / block
        val rows = height / block
        val counts = IntArray(cols * rows)
        for (p in points) {
            val gx = (p.x / block).toInt()
            val gy = (p.y / block).toInt()
            if (gx < cols && gy < rows) counts[gy * cols + gx]++
        }
        val expected = block * block / (minSpacing * minSpacing)
        for (c in counts) {
            assertTrue("Block has only $c points, expected roughly $expected", c > expected * 0.4)
        }
    }

    @Test
    fun isDeterministicForSeed() {
        val a = StippleEffect.generatePoissonPointsKotlin(width, height, minSpacing, numLayers, 7)
        val b = StippleEffect.generatePoissonPointsKotlin(width, height, minSpacing, numLayers, 7)
        val c = StippleEffect.generatePoissonPointsKotlin(width, height, minSpacing, numLayers, 8)
        assertTrue(a.contentEquals(b))
        assertTrue(!a.contentEquals(c))
    }
}
