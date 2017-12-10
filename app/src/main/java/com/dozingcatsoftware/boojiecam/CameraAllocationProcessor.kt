package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Created by brian on 10/15/17.
 */
abstract class CameraAllocationProcessor(val rs: RenderScript) {
    private var consumerThread: Thread? = null
    private var receivedCameraAllocation: CameraImage? = null
    private var lastAllocationRef: WeakReference<Allocation>? = null
    private var allocationUpdateCount = 0
    private val threadLock = ReentrantLock()
    private val allocationLock = ReentrantLock()
    private val allocationAvailable = allocationLock.newCondition()

    fun start(callback: (ProcessedBitmap) -> Unit) {
        threadLock.withLock({
            if (consumerThread == null) {
                consumerThread = Thread({this.threadEntry(callback)})
                consumerThread!!.start()
            }
        })
    }

    fun pause() {
        threadLock.withLock({
            consumerThread = null
        })
        allocationLock.withLock({
            if (lastAllocationRef != null) {
                val lastAllocation = lastAllocationRef!!.get()
                for (i in 0 until allocationUpdateCount) {
                    ioReceiveIfInput(lastAllocation)
                }
                lastAllocationRef = null
                allocationUpdateCount = 0
            }
        })
    }

    fun queueAllocation(cameraAllocation: CameraImage) {
        threadLock.withLock({
            if (consumerThread == null) {
                ioReceiveIfInput(cameraAllocation.singleYuvAllocation)
                return
            }
        })

        allocationLock.withLock({
            val allocation = cameraAllocation.singleYuvAllocation!!
            if (lastAllocationRef != null) {
                val prevAllocation = lastAllocationRef!!.get()
                if (prevAllocation == allocation) {
                    allocationUpdateCount += 1
                }
                else {
                    for (i in 0 until allocationUpdateCount) {
                        ioReceiveIfInput(prevAllocation)
                    }
                    lastAllocationRef = WeakReference(allocation)
                    allocationUpdateCount = 1
                }
            }
            else {
                lastAllocationRef = WeakReference(allocation)
                allocationUpdateCount = 1
            }
            this.receivedCameraAllocation = cameraAllocation
            allocationAvailable.signal()
            debugLog("queueAllocation, count=" + allocationUpdateCount)
        })
    }

    private fun shouldCurrentThreadContinue(): Boolean {
        return threadLock.withLock({
            Thread.currentThread() == consumerThread
        })
    }

    private fun threadEntry(callback: (ProcessedBitmap) -> Unit) {
        var currentCamAllocation: CameraImage? = null
        while (true) {
            while (currentCamAllocation == null) {
                if (!shouldCurrentThreadContinue()) {
                    return
                }
                allocationLock.withLock({
                    currentCamAllocation = receivedCameraAllocation
                    if (currentCamAllocation == null) {
                        allocationAvailable.awaitNanos(250000000)
                    }
                    else {
                        debugLog("Calling ioReceive, count="+allocationUpdateCount)
                        for (i in 0 until allocationUpdateCount) {
                            ioReceiveIfInput(currentCamAllocation!!.singleYuvAllocation)
                        }
                        allocationUpdateCount = 0
                        receivedCameraAllocation = null
                    }
                })
            }

            val bitmap = createBitmap(currentCamAllocation!!)
            val backgroundPaintFn = createPaintFn(currentCamAllocation!!)
            var yuvBytes: ByteArray? = null
            if (currentCamAllocation!!.status == CameraStatus.CAPTURING_PHOTO) {
                yuvBytes = flattenedYuvImageBytes(rs, currentCamAllocation!!.singleYuvAllocation!!)
            }
            callback(ProcessedBitmap(
                    currentCamAllocation!!, bitmap, backgroundPaintFn, yuvBytes))
            currentCamAllocation = null
        }
    }

    abstract fun createBitmap(camAllocation: CameraImage): Bitmap

    open fun createPaintFn(camAllocation: CameraImage): (RectF) -> Paint? {
        return {null}
    }

    companion object {
        val TAG = "CameraAllocProcessor"
        var DEBUG = false
        var TIMING = false

        fun debugLog(msg: String) {
            if (DEBUG) {
                Log.i(TAG, msg)
            }
        }

        fun timingLog(msg: String) {
            if (TIMING) {
                Log.i(TAG, msg)
            }
        }

        fun makeAllocationColorMap(rs: RenderScript,
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

        fun makeAlphaAllocation(rs: RenderScript, color: Int): Allocation {
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