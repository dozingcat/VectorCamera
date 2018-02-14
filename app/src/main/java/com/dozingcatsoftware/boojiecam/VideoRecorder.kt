package com.dozingcatsoftware.boojiecam

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


private data class Frame(val timestamp: Long, val data: ByteArray)

class VideoRecorder(val videoId: String, val videoOutput: OutputStream,
                    val frameCallback: ((VideoRecorder, Status) -> Unit)?) {
    enum class Status {NOT_STARTED, STARTING, RUNNING, STOPPING, FINISHED}

    // Timestamps are absolute milliseconds, but only relative differences matter.
    val frameTimestamps = mutableListOf<Long>()

    private var status = Status.NOT_STARTED
    private var writerThread: Thread? = null
    private val writerThreadLock = ReentrantLock()
    private val frameQueue = mutableListOf<Frame>()
    private val frameQueueLock = ReentrantLock()
    private val frameAvailable = frameQueueLock.newCondition()

    fun start() {
        status = Status.STARTING
        if (writerThread != null) {
            throw IllegalStateException("Already started")
        }
        writerThread = Thread(this::threadEntry)
        writerThread!!.start()
    }

    fun recordFrame(timestamp: Long, frameBytes: ByteArray) {
        Log.i("VideoRecorder", "recordFrame: ${timestamp}, ${frameBytes.size} bytes")
        frameQueueLock.withLock({
            if (frameQueue.size < MAX_QUEUED_FRAMES) {
                frameQueue.add(Frame(timestamp, frameBytes))
                frameAvailable.signal()
            }
            else {
                Log.w("VideoRecorder", "Dropping frame")
            }
        })
    }

    fun stop() {
        writerThreadLock.withLock({
            status = Status.STOPPING
        })
    }

    private fun writerThreadShouldExit(): Boolean {
        writerThreadLock.withLock({
            return status == Status.STOPPING && frameQueue.isEmpty()
        })
    }

    private fun acquireFrameFromQueue(): Frame? {
        frameQueueLock.withLock({
            if (frameQueue.isEmpty()) {
                return null
            }
            return frameQueue.removeAt(0)
        })
    }

    private fun threadEntry() {
        var currentFrame: Frame? = null
        var framesRead = 0
        this.status = Status.RUNNING
        try {
            while (true) {
                while (currentFrame == null) {
                    if (writerThreadShouldExit()) {
                        Log.i(TAG, "Exiting")
                        videoOutput.close()
                        this.status = Status.FINISHED
                        frameCallback?.invoke(this, this.status)
                        return
                    }
                    currentFrame = acquireFrameFromQueue()
                    if (currentFrame == null) {
                        Log.i(TAG, "Frame not available, waiting, status=${status}")
                        frameQueueLock.withLock {
                            frameAvailable.awaitNanos(250000000)
                        }
                    }
                }
                framesRead += 1
                Log.i(TAG, "Got frame ${framesRead}")
                frameTimestamps.add(currentFrame.timestamp)
                // We could gzip the frames, but compression is very slow.
                videoOutput.write(currentFrame.data)
                frameCallback?.invoke(this, this.status)
                currentFrame = null
            }
        }
        finally {
            Log.i(TAG, "VideoRecorder thread exiting")
            try {videoOutput.close()}
            catch (ignored: Exception) {}
        }
    }

    companion object {
        val TAG = "VideoRecorder"
        val MAX_QUEUED_FRAMES = 3
    }
}
