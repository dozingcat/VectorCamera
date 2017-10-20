package com.dozingcatsoftware.boojiecam

import android.graphics.*
import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class CameraImageProcessor: AbstractImageProcessor {
    private var consumerThread: Thread? = null
    private var nextImage: CameraImage? = null
    private val threadLock = ReentrantLock()
    private val imageLock = ReentrantLock()
    private val imageAvailable = imageLock.newCondition()
    protected val maxThreads = Math.min(Runtime.getRuntime().availableProcessors(), 4)

    fun start(callback: (ProcessedBitmap) -> Unit) {
        threadLock.withLock({
            if (consumerThread == null) {
                consumerThread = Thread({this.threadEntry(callback)})
                consumerThread!!.start()
            }
        })

    }

    override fun pause() {
        debugLog("CameraImageProcessor.pause")
        threadLock.withLock({
            consumerThread = null
        })
        imageLock.withLock({
            nextImage?.close()
            nextImage = null
        })
    }

    fun queueImage(image: CameraImage) {
        threadLock.withLock({
            if (consumerThread == null) {
                image.close()
                return
            }
        })
        imageLock.withLock({
            debugLog("Setting image: " + image.hashCode())
            if (nextImage != null) {
                debugLog("Closing previous: " + nextImage!!.hashCode())
                nextImage!!.close()
                nextImage = image
            }
            else {
                nextImage = image
                imageAvailable.signal()
            }
        })
    }

    private fun shouldCurrentThreadContinue(): Boolean {
        return threadLock.withLock({
            Thread.currentThread() == consumerThread
        })
    }

    private fun threadEntry(callback: (ProcessedBitmap) -> Unit) {
        debugLog("Thread started")
        var image: CameraImage? = null
        while (true) {
            while (image == null) {
                if (!shouldCurrentThreadContinue()) {
                    return
                }
                imageLock.withLock({
                    image = nextImage
                    if (image == null) {
                        debugLog("Waiting for image")
                        imageAvailable.awaitNanos(250000000)
                    }
                    else {
                        debugLog("Got image: " + image!!.hashCode())
                        nextImage = null
                    }
                })
            }
            if (!shouldCurrentThreadContinue()) {
                debugLog("Closing image before thread exits: " + image!!.hashCode())
                image!!.close()
                image = null
                return
            }
            try {
                val bitmap = createBitmapFromImage(image!!.image)
                val backgroundPaintFn = createPaintFn(image!!.image)
                image!!.close()
                callback(ProcessedBitmap(image!!, null, bitmap, backgroundPaintFn))
                image = null
            }
            catch (ex: Exception) {
                Log.e(TAG, "Error with image: " + image?.hashCode(), ex)
                if (ex is IllegalStateException) {
                    // Terrible, but images keep getting closed out from under us.
                }
                else {
                    throw ex
                }
            }
            finally {
                if (image != null) {
                    debugLog("Closing image after processing: " + (image?.hashCode()))
                    image?.close()
                    image = null
                }
            }
        }
    }

    abstract fun createBitmapFromImage(image: PlanarImage): Bitmap

    open fun createBitmapFromAllocation(allocation: CameraAllocation): Bitmap? {
        return null
    }

    open fun createPaintFn(image: PlanarImage): (RectF) -> Paint? {
        return {null}
    }

    companion object {
        val TAG = "CameraImageProcessor"
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
