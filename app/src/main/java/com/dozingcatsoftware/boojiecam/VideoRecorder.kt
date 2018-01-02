package com.dozingcatsoftware.boojiecam

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


private data class Frame(val timestamp: Long, val data: ByteArray)

class VideoRecorder(val videoId: String, val videoOutput: OutputStream,
                    val frameCallback: ((VideoRecorder) -> Unit)?) {
    enum class Status {NOT_STARTED, STARTING, RUNNING, STOPPING, FINISHED}

    // Timestamps are absolute milliseconds, but only relative differences matter.
    val frameTimestamps = mutableListOf<Long>()

    var status = Status.NOT_STARTED
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
        while (true) {
            while (currentFrame == null) {
                if (writerThreadShouldExit()) {
                    videoOutput.close()
                    this.status = Status.FINISHED
                    frameCallback?.invoke(this)
                    return
                }
                while (currentFrame == null) {
                    currentFrame = acquireFrameFromQueue()
                }
                if (currentFrame == null) {
                    frameAvailable.awaitNanos(250000000)
                }
            }
            // For some reason the first frames are all zero bytes, which turns into a green square.
            framesRead += 1
            if (framesRead < 10) {
                currentFrame = null
                continue
            }
            frameTimestamps.add(currentFrame.timestamp)
            // We could gzip the frames, but compression is very slow.
            videoOutput.write(currentFrame!!.data)
            frameCallback?.invoke(this)
            currentFrame = null
        }
    }

    companion object {
        val MAX_QUEUED_FRAMES = 3
    }
}
