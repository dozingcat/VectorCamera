package com.dozingcatsoftware.boojiecam

import android.graphics.*
import android.util.Log
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
            nextImage?.closeImage()
            nextImage = null
        })
    }

    fun queueImage(image: CameraImage) {
        threadLock.withLock({
            if (consumerThread == null) {
                image.closeImage()
                return
            }
        })
        imageLock.withLock({
            debugLog("Setting image: " + image.hashCode())
            if (nextImage != null) {
                debugLog("Closing previous: " + nextImage!!.hashCode())
                nextImage!!.closeImage()
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
                image!!.closeImage()
                image = null
                return
            }
            try {
                val planarImage = image!!.image!!
                val bitmap = createBitmapFromImage(planarImage)
                val backgroundPaintFn = createPaintFn(planarImage)
                var yuvBytes: ByteArray? = null
                if (image!!.status == CameraStatus.CAPTURING_PHOTO) {
                    yuvBytes = flattenedYuvImageBytes(planarImage)
                }
                image!!.closeImage()
                callback(ProcessedBitmap(image!!, bitmap, backgroundPaintFn, yuvBytes))
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
                    image?.closeImage()
                    image = null
                }
            }
        }
    }

    abstract fun createBitmapFromImage(image: PlanarImage): Bitmap

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
