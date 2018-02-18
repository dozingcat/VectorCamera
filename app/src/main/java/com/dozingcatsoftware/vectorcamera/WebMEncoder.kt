package com.dozingcatsoftware.vectorcamera

import java.io.File

/**
 * Created by brian on 1/21/18.
 */
class WebMEncoder(val videoReader: VideoReader, val outputPath: String) {

    private val frameArgb = IntArray(videoReader.videoWidth() * videoReader.videoHeight())
    private val numFrames = videoReader.numberOfFrames()
    private val videoWidth = videoReader.videoWidth()
    private val videoHeight = videoReader.videoHeight()

    fun startEncoding() {
        val framesPerSecond = (numFrames / videoReader.totalDurationMillis()).toFloat() / 1000f
        val frameRelativeEndTimes = IntArray(numFrames)
        frameRelativeEndTimes[0] = videoReader.frameDurationMillis(0).toInt()
        for (i in 1 until numFrames) {
            frameRelativeEndTimes[i] =
                    frameRelativeEndTimes[i - 1] + videoReader.frameDurationMillis(i).toInt()
        }
        val deadlineMicros = 1_000_000
        this.nativeStartEncoding(
                outputPath, videoWidth, videoHeight,
                framesPerSecond, frameRelativeEndTimes, deadlineMicros)
    }

    fun encodeFrame(frameIndex: Int) {
        if (frameIndex < 0 || frameIndex >= numFrames) {
            throw IllegalArgumentException("Invalid frame index: ${frameIndex}")
        }
        val bitmap = videoReader.bitmapForFrame(frameIndex).renderBitmap(videoWidth, videoHeight)
        bitmap.getPixels(frameArgb, 0, videoWidth, 0, 0, videoWidth, videoHeight)
        nativeEncodeFrame(frameArgb)
    }

    fun finishEncoding() {
        nativeFinishEncoding()
    }

    fun cancelEncoding() {
        nativeCancelEncoding()
        File(outputPath).delete()
    }

    external fun nativeStartEncoding(
            path: String, width: Int, height: Int,
            fps: Float, frameEndTimes: IntArray, deadline: Int): Int
    external fun nativeEncodeFrame(argb: IntArray): Int
    external fun nativeFinishEncoding(): Int
    external fun nativeCancelEncoding(): Int

    companion object {
        val TAG = "WebMEncoder"

        init {
            System.loadLibrary("vectorcamera")
        }
    }
}