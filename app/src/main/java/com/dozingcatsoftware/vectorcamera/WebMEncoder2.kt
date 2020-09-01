package com.dozingcatsoftware.vectorcamera

import android.util.Log
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.dozingcatsoftware.util.YuvImageBuffers
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.NullPointerException
import java.nio.ByteBuffer

/**
 * Encodes a series of video frames to a WebM file using the MedicCodec and MediaMuxer APIs.
 */
class WebMEncoder2(val videoReader: VideoReader, val outputPath: String) {

    private val numFrames = videoReader.numberOfFrames()
    private val outputWidth = videoReader.outputVideoWidth()
    private val outputHeight = videoReader.outputVideoHeight()

    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var videoTrackIndex = -1
    private lateinit var frameRelativeEndTimes: IntArray

    private val frameArgb = IntArray(outputWidth * outputHeight)
    private val bufferInfo = MediaCodec.BufferInfo()


    fun startEncoding() {
        val mimeType = MediaFormat.MIMETYPE_VIDEO_VP8
        val framesPerSecond = (numFrames / videoReader.totalDurationMillis()).toFloat() / 1000f
        encoder = MediaCodec.createEncoderByType(mimeType)
        val format = MediaFormat.createVideoFormat(mimeType, outputWidth, outputHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 125000)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setFloat(MediaFormat.KEY_FRAME_RATE, framesPerSecond)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        videoTrackIndex = muxer.addTrack(format)
        muxer.start()
        // outputStream = FileOutputStream(outputPath)

        val arch = System.getProperty("os.arch")
        Log.i(TAG, "Starting video encoding, os.arch=${arch}")
        frameRelativeEndTimes = IntArray(numFrames)
        frameRelativeEndTimes[0] = videoReader.frameDurationMillis(0).toInt()
        for (i in 1 until numFrames) {
            frameRelativeEndTimes[i] =
                    frameRelativeEndTimes[i - 1] + videoReader.frameDurationMillis(i).toInt()
        }
    }

    private fun sendFrameToEncoder(frameIndex: Int) {
        if (frameIndex < 0 || frameIndex >= numFrames) {
            throw IllegalArgumentException("Invalid frame index: ${frameIndex}")
        }
        val inputBufferId = encoder.dequeueInputBuffer(1_000_000)
        Log.i("ENC", "inputBufferId: ${inputBufferId}")
        if (inputBufferId < 0) {
            // throw RuntimeException("Can't dequeue input buffer")
            return
        }
        val inputBuffer: ByteBuffer = encoder.getInputBuffer(inputBufferId)
                ?: throw NullPointerException("Input buffer is null")

        // Render a bitmap with the selected effect, extract the pixels, and send to the encoder.
        val bitmap = videoReader.bitmapForFrame(frameIndex).renderBitmap(
                videoReader.landscapeVideoWidth(), videoReader.landscapeVideoHeight())
        val yuvBuffers = YuvImageBuffers.fromBitmap(bitmap)
        // Assuming the input buffer should be in YUV order.
        inputBuffer.position(0)
        inputBuffer.put(yuvBuffers.y)
        inputBuffer.put(yuvBuffers.u)
        inputBuffer.put(yuvBuffers.v)

        val offsetMicros = if (frameIndex == 0) 0 else frameRelativeEndTimes[frameIndex - 1] * 1000L
        val flags = if (frameIndex == numFrames - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        encoder.queueInputBuffer(inputBufferId, 0, videoReader.bytesPerFrame(), offsetMicros, flags)
    }

    private fun writeEncoderOutput() {
        val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 1_000_000)
        if (outputBufferId < 0) {
            // throw RuntimeException("Can't dequeue output buffer")
            return
        }
        val outputBuffer = encoder.getOutputBuffer(outputBufferId)
                ?: throw NullPointerException("Output buffer is null")
        Log.i("ENC", "Writing ${bufferInfo.size} encoded bytes to muxer")
        muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
        encoder.releaseOutputBuffer(outputBufferId, false)
    }

    fun encodeFrame(frameIndex: Int) {
        sendFrameToEncoder(frameIndex)
        writeEncoderOutput()
        /*
        val bitmap = videoReader.bitmapForFrame(frameIndex).renderBitmap(
                videoReader.landscapeVideoWidth(), videoReader.landscapeVideoHeight())
        bitmap.getPixels(frameArgb, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        nativeEncodeFrame(frameArgb)
        */
    }

    fun finishEncoding() {
        // nativeFinishEncoding()
        encoder.stop()
        muxer.stop()
        // outputStream.close()
    }

    fun cancelEncoding() {
        // nativeCancelEncoding()
        encoder.stop()
        muxer.stop()
        // outputStream.close()
        File(outputPath).delete()
    }

    /*
    external fun nativeStartEncoding(
            path: String, width: Int, height: Int,
            fps: Float, frameEndTimes: IntArray, deadline: Int): Int
    external fun nativeEncodeFrame(argb: IntArray): Int
    external fun nativeFinishEncoding(): Int
    external fun nativeCancelEncoding(): Int
     */

    companion object {
        const val TAG = "WebMEncoder2"

        init {
            System.loadLibrary("vectorcamera")
        }
    }
}