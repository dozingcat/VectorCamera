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
        /*
        threadLock.withLock({
            if (consumerThread == null) {
                // If there's no consumer thread, we still need to consume the RenderScript allocation
                val allocation = cameraImage.getSingleYuvAllocation()
                if (allocation != null) {
                    ioReceiveIfInput(allocation)
                }
                // Note: No need to close ImageData - the original Image was closed immediately
                return
            }
        })
        */

        allocationLock.withLock({
            /*
            val allocation = cameraImage.getSingleYuvAllocation()
            if (allocation != null) {
                // Handle RenderScript allocation tracking
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
            }

             */

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
//                        val allocation = currentCamAllocation!!.getSingleYuvAllocation()
//                        if (allocation != null) {
//                            for (i in 0 until allocationUpdateCount) {
//                                ioReceiveIfInput(allocation)
//                            }
//                        }
                        allocationUpdateCount = 0
                        receivedCameraImage = null
                    }
                })
            }

            val bitmap = effect.createBitmap(currentCamAllocation!!)
            // Get the flattened bytes if we need them. For ImageData, we can get them directly.
            // For legacy RenderScript allocations, we use the flatten_yuv script.
            val yuvBytes = if (currentCamAllocation!!.status.isSavingImage()) {
                // Try to get YUV bytes directly from ImageData first
                currentCamAllocation!!.getYuvBytes() ?: run {
                    // Fallback to RenderScript for legacy allocations
                    val singleYuvAlloc = currentCamAllocation!!.getSingleYuvAllocation()
                    if (singleYuvAlloc != null) {
                        flattenedYuvImageBytes(rs, singleYuvAlloc)
                    } else {
                        null
                    }
                }
            } else {
                null
            }
            
            val processedBitmap = ProcessedBitmap(
                    effect, currentCamAllocation!!, bitmap, yuvBytes)
            
            // Note: No need to close ImageData - the original Image was closed immediately
            
            callback(processedBitmap)
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