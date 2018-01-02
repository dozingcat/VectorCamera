package com.dozingcatsoftware.boojiecam

import android.renderscript.Allocation
import android.renderscript.RenderScript
import android.util.Log
import com.dozingcatsoftware.boojiecam.effect.Effect
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Created by brian on 10/15/17.
 */
class CameraAllocationProcessor(val rs: RenderScript) {
    private var consumerThread: Thread? = null
    private var receivedCameraAllocation: CameraImage? = null
    private var lastAllocationRef: WeakReference<Allocation>? = null
    private var allocationUpdateCount = 0
    private val threadLock = ReentrantLock()
    private val allocationLock = ReentrantLock()
    private val allocationAvailable = allocationLock.newCondition()
    private lateinit var effect: Effect

    fun start(effect: Effect, callback: (ProcessedBitmap) -> Unit) {
        this.pause()
        this.effect = effect
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
            debugLog("queueAllocation, count=${allocationUpdateCount}")
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
                        debugLog("Calling ioReceive, count=${allocationUpdateCount}")
                        for (i in 0 until allocationUpdateCount) {
                            ioReceiveIfInput(currentCamAllocation!!.singleYuvAllocation)
                        }
                        allocationUpdateCount = 0
                        receivedCameraAllocation = null
                    }
                })
            }

            val bitmap = effect.createBitmap(currentCamAllocation!!)
            val backgroundPaintFn = effect.createPaintFn(currentCamAllocation!!)
            // Get the flattened bytes if we need them. RenderScript seems to not play well with
            // threads, so we don't want to try to parallelize this.
            val yuvBytes =
                    if (currentCamAllocation!!.status.isSavingImage())
                        flattenedYuvImageBytes(rs, currentCamAllocation!!.singleYuvAllocation!!)
                    else null
            callback(ProcessedBitmap(
                    effect, currentCamAllocation!!, bitmap, backgroundPaintFn, yuvBytes))
            currentCamAllocation = null
        }
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