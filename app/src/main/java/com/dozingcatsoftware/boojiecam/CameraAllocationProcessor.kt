package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.renderscript.Allocation
import android.renderscript.RenderScript
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Created by brian on 10/15/17.
 */
abstract class CameraAllocationProcessor(val rs: RenderScript): AbstractImageProcessor {
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

    override fun pause() {
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
                ioReceiveIfInput(cameraAllocation.allocation)
                return
            }
        })

        allocationLock.withLock({
            val allocation = cameraAllocation.allocation
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
                            ioReceiveIfInput(currentCamAllocation!!.allocation)
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
                yuvBytes = flattenedYuvImageBytes(rs, currentCamAllocation!!.allocation!!)
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
    }
}