package com.dozingcatsoftware.vectorcamera

import android.renderscript.RenderScript
import android.util.Log
import com.dozingcatsoftware.vectorcamera.effect.Effect
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CameraImageProcessor(val rs: RenderScript) {
    private var consumerThread: Thread? = null
    private var receivedCameraImage: CameraImage? = null
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
            if (receivedCameraImage != null) {
                receivedCameraImage = null
            }
        })
    }

    fun queueCameraImage(cameraImage: CameraImage) {
        allocationLock.withLock({
            this.receivedCameraImage = cameraImage
            allocationAvailable.signal()
            debugLog("queueCameraImage")
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
                        debugLog("Processing camera image")
                        receivedCameraImage = null
                    }
                })
            }

            val t1 = System.nanoTime()
            val bitmap = effect.createBitmap(currentCamAllocation!!)
            val duration = System.nanoTime() - t1
            // Get the flattened bytes if we need them. For ImageData, we can get them directly.
            // For legacy RenderScript allocations, we use the flatten_yuv script.
            val yuvBytes = if (currentCamAllocation!!.status.isSavingImage()) {
                // Get YUV bytes directly from ImageData
                currentCamAllocation!!.getYuvBytes()
            } else {
                null
            }
            
            val processedBitmap = ProcessedBitmap(
                    effect, currentCamAllocation!!, bitmap, yuvBytes, generationTimeNanos=duration)
            
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