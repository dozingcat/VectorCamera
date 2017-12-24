package com.dozingcatsoftware.boojiecam

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.withLock


private data class Frame(val timestamp: Long, val data: ByteArray)

class VideoRecorder(val videoOutput: OutputStream, val frameCallback: ((VideoRecorder) -> Unit)?) {
    enum class Status {NOT_STARTED, STARTING, RUNNING, STOPPING, FINISHED}

    // Timestamps are absolute milliseconds, but only relative differences matter.
    val frameTimestamps = mutableListOf<Long>()
    val frameByteOffsets = mutableListOf<Long>()

    private var status = Status.NOT_STARTED
    private var writerThread: Thread? = null
    private val writerThreadLock = ReentrantLock()
    private val frameQueue = mutableListOf<Frame>()
    private val frameQueueLock = ReentrantLock()
    private val frameAvailable = frameQueueLock.newCondition()
    var bytesWritten = 0L

    fun start() {
        status = Status.STARTING
        if (writerThread != null) {
            throw IllegalStateException("Already started")
        }
        writerThread = Thread(this::_threadEntry)
        writerThread!!.start()
    }

    fun recordFrame(timestamp: Long, frameBytes: ByteArray) {
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

    private fun _threadEntry() {
        var currentFrame: Frame? = null
        var compressedBuffer = ByteArrayOutputStream()
        while (true) {
            while (currentFrame == null) {
                if (writerThreadShouldExit()) {
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

            frameTimestamps.add(currentFrame.timestamp)
            frameByteOffsets.add(bytesWritten)
            compressedBuffer.reset()
            GZIPOutputStream(compressedBuffer).use({
                it.write(currentFrame!!.data)
            })
            compressedBuffer.writeTo(videoOutput)

            bytesWritten += compressedBuffer.size()
            frameCallback?.invoke(this)
            currentFrame = null
        }
    }

    companion object {
        val MAX_QUEUED_FRAMES = 3
    }
}
