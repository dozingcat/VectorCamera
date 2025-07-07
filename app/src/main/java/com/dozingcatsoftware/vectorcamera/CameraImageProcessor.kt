package com.dozingcatsoftware.vectorcamera

import android.renderscript.Allocation
import android.renderscript.RenderScript
import android.util.Log
import com.dozingcatsoftware.vectorcamera.effect.Effect
import com.dozingcatsoftware.util.flattenedYuvImageBytes
import com.dozingcatsoftware.util.ioReceiveIfInput
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CameraImageProcessor(val rs: RenderScript) {
    private var consumerThread: Thread? = null
    private var receivedCameraImage: CameraImage? = null
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

    fun queueCameraImage(cameraImage: CameraImage) {
        threadLock.withLock({
            if (consumerThread == null) {
                ioReceiveIfInput(cameraImage.getSingleYuvAllocation())
                return
            }
        })

        allocationLock.withLock({
            val allocation = cameraImage.getSingleYuvAllocation()!!
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
            this.receivedCameraImage = cameraImage
            allocationAvailable.signal()
            debugLog("queueCameraImage, count=${allocationUpdateCount}")
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
                    currentCamAllocation = receivedCameraImage
                    if (currentCamAllocation == null) {
                        allocationAvailable.awaitNanos(250000000)
                    }
                    else {
                        debugLog("Calling ioReceive, count=${allocationUpdateCount}")
                        for (i in 0 until allocationUpdateCount) {
                            ioReceiveIfInput(currentCamAllocation!!.getSingleYuvAllocation())
                        }
                        allocationUpdateCount = 0
                        receivedCameraImage = null
                    }
                })
            }

            val bitmap = effect.createBitmap(currentCamAllocation!!)
            // Get the flattened bytes if we need them. RenderScript seems to not play well with
            // threads, so we don't want to try to parallelize this.
            val yuvBytes =
                    if (currentCamAllocation!!.status.isSavingImage())
                        flattenedYuvImageBytes(rs, currentCamAllocation!!.getSingleYuvAllocation()!!)
                    else null
            callback(ProcessedBitmap(
                    effect, currentCamAllocation!!, bitmap, yuvBytes))
            currentCamAllocation = null
        }
    }

    companion object {
        const val TAG = "CameraAllocProcessor"
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