package com.dozingcatsoftware.boojiecam

import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type

/**
 * Created by brian on 10/18/17.
 */
class EdgeAllocationProcessor(rs: RenderScript,
                              private val colorTable: Allocation,
                              private val paintFn: (CameraAllocation, RectF) -> Paint?):
        CameraAllocationProcessor(rs) {
    private var outputAllocation: Allocation? = null
    private var script: ScriptC_edge? = null

    override fun createBitmap(camAllocation: CameraAllocation): Bitmap {
        if (script == null) {
            script = ScriptC_edge(rs)
        }
        val allocation = camAllocation.allocation
        script!!._gYuvInput = allocation
        script!!._gWidth = allocation.type.x
        script!!._gHeight = allocation.type.y
        script!!._gMultiplier = minOf(4, maxOf(2, Math.round(allocation.type.x / 480f)))
        script!!._gColorMap = colorTable

        if (outputAllocation == null ||
                outputAllocation!!.type.x != allocation.type.x ||
                outputAllocation!!.type.y != allocation.type.y) {
            val outputTypeBuilder = Type.Builder(rs, Element.RGBA_8888(rs))
            outputTypeBuilder.setX(allocation.type.x)
            outputTypeBuilder.setY(allocation.type.y)
            outputAllocation = Allocation.createTyped(rs, outputTypeBuilder.create(),
                    Allocation.USAGE_SCRIPT)
        }

        script!!.forEach_computeEdgeWithColorMap(outputAllocation)

        val resultBitmap = Bitmap.createBitmap(
                allocation.type.x, allocation.type.y, Bitmap.Config.ARGB_8888)
        outputAllocation!!.copyTo(resultBitmap)

        return resultBitmap
    }

    override fun createPaintFn(camAllocation: CameraAllocation): (RectF) -> Paint? {
        return {rect -> paintFn(camAllocation, rect)}
    }

    companion object {
        fun withFixedColors(rs: RenderScript,
                            minEdgeColor: Int, maxEdgeColor: Int): EdgeAllocationProcessor {
            return EdgeAllocationProcessor(
                    rs, makeAllocationColorMap(rs, minEdgeColor, maxEdgeColor), {_, _ -> null})
        }

        fun withLinearGradient(
                rs: RenderScript, minEdgeColor: Int,
                gradientStartColor: Int, gradientEndColor: Int): EdgeAllocationProcessor {
            val paintFn = fun(_: CameraAllocation, rect: RectF): Paint {
                val p = Paint()
                p.shader = LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        addAlpha(gradientStartColor), addAlpha(gradientEndColor),
                        Shader.TileMode.MIRROR)
                return p
            }
            return EdgeAllocationProcessor(rs, makeAlphaAllocation(rs, minEdgeColor), paintFn)
        }

        fun withRadialGradient(
                rs: RenderScript, minEdgeColor: Int,
                centerColor: Int, outerColor: Int): EdgeAllocationProcessor {
            val paintFn = fun(_: CameraAllocation, rect: RectF): Paint {
                val p = Paint()
                p.shader = RadialGradient(
                        rect.width() / 2, rect.height() / 2,
                        maxOf(rect.width(), rect.height()) / 2f,
                        addAlpha(centerColor), addAlpha(outerColor), Shader.TileMode.MIRROR)
                return p
            }
            return EdgeAllocationProcessor(rs, makeAlphaAllocation(rs, minEdgeColor), paintFn)
        }

        private fun makeAllocationColorMap(rs: RenderScript,
                                           minEdgeColor: Int, maxEdgeColor: Int, size: Int=256): Allocation {
            val r0 = (minEdgeColor shr 16) and 0xff
            val g0 = (minEdgeColor shr 8) and 0xff
            val b0 = (minEdgeColor) and 0xff
            val r1 = (maxEdgeColor shr 16) and 0xff
            val g1 = (maxEdgeColor shr 8) and 0xff
            val b1 = (maxEdgeColor) and 0xff
            val sizef = size.toFloat()

            val colors = ByteArray(size * 4)
            var bindex = 0
            for (index in 0 until size) {
                val fraction = index / sizef
                // Allocations are RGBA even though bitmaps are ARGB.
                colors[bindex++] = Math.round(r0 + (r1 - r0) * fraction).toByte()
                colors[bindex++] = Math.round(g0 + (g1 - g0) * fraction).toByte()
                colors[bindex++] = Math.round(b0 + (b1 - b0) * fraction).toByte()
                colors[bindex++] = 0xff.toByte()
            }
            val type = Type.Builder(rs, Element.RGBA_8888(rs))
            type.setX(size)
            val allocation = Allocation.createTyped(rs, type.create(), Allocation.USAGE_SCRIPT)
            allocation.copyFrom(colors)
            return allocation
        }

        private fun makeAlphaAllocation(rs: RenderScript, color: Int): Allocation {
            val r = ((color shr 16) and 0xff).toByte()
            val g = ((color shr 8) and 0xff).toByte()
            val b = ((color) and 0xff).toByte()
            val colors = ByteArray(4 * 256)
            var bindex = 0
            for (index in 0 until 256) {
                colors[bindex++] = r
                colors[bindex++] = g
                colors[bindex++] = b
                colors[bindex++] = (255 - index).toByte()
            }
            val type = Type.Builder(rs, Element.RGBA_8888(rs))
            type.setX(256)
            val allocation = Allocation.createTyped(rs, type.create(), Allocation.USAGE_SCRIPT)
            allocation.copyFrom(colors)
            return allocation
        }
    }
}