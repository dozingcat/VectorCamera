package com.dozingcatsoftware.vectorcamera

import android.util.Log
import java.io.File

/**
 * Encodes a series of video frames to a WebM file using a native library.
 * See vc_video.c
 */
class WebMEncoder(val videoReader: VideoReader, val outputPath: String) {

    private val frameArgb = IntArray(videoReader.outputVideoWidth() * videoReader.outputVideoHeight())
    private val numFrames = videoReader.numberOfFrames()
    private val outputWidth = videoReader.outputVideoWidth()
    private val outputHeight = videoReader.outputVideoHeight()

    fun startEncoding() {
        val arch = System.getProperty("os.arch")
        Log.i(TAG, "Starting video encoding, os.arch=${arch}")
        val framesPerSecond = (numFrames / videoReader.totalDurationMillis()).toFloat() / 1000f
        val frameRelativeEndTimes = IntArray(numFrames)
        frameRelativeEndTimes[0] = videoReader.frameDurationMillis(0).toInt()
        for (i in 1 until numFrames) {
            frameRelativeEndTimes[i] =
                    frameRelativeEndTimes[i - 1] + videoReader.frameDurationMillis(i).toInt()
        }
        val deadlineMicros = 1_000_000
        this.nativeStartEncoding(
                outputPath, outputWidth, outputHeight,
                framesPerSecond, frameRelativeEndTimes, deadlineMicros)
    }

    fun encodeFrame(frameIndex: Int) {
        if (frameIndex < 0 || frameIndex >= numFrames) {
            throw IllegalArgumentException("Invalid frame index: ${frameIndex}")
        }
        val bitmap = videoReader.bitmapForFrame(frameIndex).renderBitmap(
                videoReader.landscapeVideoWidth(), videoReader.landscapeVideoHeight())
        bitmap.getPixels(frameArgb, 0, outputWidth, 0, 0, outputWidth, outputHeight)
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
        const val TAG = "WebMEncoder"

        init {
            System.loadLibrary("vectorcamera")
        }
    }
}