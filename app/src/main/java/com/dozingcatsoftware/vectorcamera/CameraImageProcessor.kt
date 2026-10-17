package com.dozingcatsoftware.vectorcamera


import android.content.Context
import android.util.Log
import com.dozingcatsoftware.vectorcamera.effect.Effect
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CameraImageProcessor(private val context: Context) {
    private var consumerThread: Thread? = null
    private var receivedCameraImage: CameraImage? = null
    private val threadLock = ReentrantLock()
    private val allocationLock = ReentrantLock()
    private val allocationAvailable = allocationLock.newCondition()
    @Volatile private var processFn: ((CameraImage) -> Unit)? = null

    /** Applies `effect` to each camera image and passes the result to `callback`. */
    fun start(effect: Effect, callback: (ProcessedBitmap) -> Unit) {
        start({cameraImage -> callback(effect.createBitmap(cameraImage))})
    }

    /** Calls `processFn` with each camera image on the processing thread. */
    fun start(processFn: (CameraImage) -> Unit) {
        this.pause()
        this.processFn = processFn
        threadLock.withLock({
            if (consumerThread == null) {
                consumerThread = Thread({this.threadEntry()})
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

    private fun threadEntry() {
        val performanceHint = ProcessingPerformanceHint(context)
        var currentCamAllocation: CameraImage? = null
        while (true) {
            while (currentCamAllocation == null) {
                if (!shouldCurrentThreadContinue()) {
                    performanceHint.close()
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

            performanceHint.beginFrame()
            processFn?.invoke(currentCamAllocation)
            performanceHint.endFrame()
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