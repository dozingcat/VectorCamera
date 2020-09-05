package com.dozingcatsoftware.vectorcamera

import android.util.Log
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.dozingcatsoftware.util.YuvImageBuffers
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a series of video frames to a WebM file using the MedicCodec and MediaMuxer APIs.
 */
class WebMEncoder(private val videoReader: VideoReader, private val outputPath: String) {

    private val numFrames = videoReader.numberOfFrames()
    // When encoding WebM video, there seems to be a buffer that can be larger than the video
    // dimensions. If it is larger, then writing image pixel data results in misaligned images
    // because the encoder is reading more pixels per line than we've provided. There doesn't seem
    // to be a way to determine the internal buffer size, but it seems to be aligned to a 16 pixel
    // boundary. So we truncate the dimensions here to make the buffer size match the video size.
    private val outputWidth = (videoReader.outputVideoWidth() / 16) * 16
    private val outputHeight = (videoReader.outputVideoHeight() / 16) * 16
    // U and V planes each have 1/4 the number of samples as the number of pixels.
    private val bytesPerFrame = outputWidth * outputHeight * 3 / 2

    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var videoTrackIndex = -1
    private lateinit var frameRelativeEndTimes: IntArray

    private val bufferInfo = MediaCodec.BufferInfo()


    fun startEncoding() {
        val mimeType = MediaFormat.MIMETYPE_VIDEO_VP8
        val framesPerSecond = (numFrames / videoReader.totalDurationMillis()).toFloat() / 1000f
        encoder = MediaCodec.createEncoderByType(mimeType)
        val format = MediaFormat.createVideoFormat(mimeType, outputWidth, outputHeight).apply {
            // Bit rate and I-frame values are guesses based on experiments, could be configurable.
            setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
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
            throw RuntimeException("Can't dequeue input buffer")
        }
        val inputBuffer: ByteBuffer = encoder.getInputBuffer(inputBufferId)
                ?: throw NullPointerException("Input buffer is null")

        // Render a bitmap with the selected effect, extract the pixels, and send to the encoder.
        // HERE: This is working, except on Pixel3a in portrait which has weird artifacts.
        // THEORY: Width must be a multiple of 16(?). 480x640 is fine, 360x640 has lines, and the
        // "buffer size" in VLC is 368x640.
        val bitmap = videoReader.bitmapForFrame(frameIndex).renderBitmap(
                videoReader.landscapeVideoWidth(), videoReader.landscapeVideoHeight())

        val yuvBuffers = YuvImageBuffers.fromBitmap(bitmap, outputWidth, outputHeight)
        // Assuming the input buffer should be in YUV order.
        inputBuffer.position(0)
        inputBuffer.put(yuvBuffers.y)
        inputBuffer.put(yuvBuffers.u)
        inputBuffer.put(yuvBuffers.v)

        val offsetMicros = if (frameIndex == 0) 0 else frameRelativeEndTimes[frameIndex - 1] * 1000L
        val flags = if (frameIndex == numFrames - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        encoder.queueInputBuffer(inputBufferId, 0, bytesPerFrame, offsetMicros, flags)
    }

    private fun writeEncoderOutput() {
        val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 1_000_000)
        // There's not always an output buffer; the encoder may need more input.
        if (outputBufferId < 0) {
            return
        }
        val outputBuffer = encoder.getOutputBuffer(outputBufferId)
                ?: throw NullPointerException("Output buffer is null")
        Log.i(TAG, "Writing ${bufferInfo.size} encoded bytes to muxer")
        muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
        encoder.releaseOutputBuffer(outputBufferId, false)
    }

    fun encodeFrame(frameIndex: Int) {
        sendFrameToEncoder(frameIndex)
        writeEncoderOutput()
    }

    fun finishEncoding() {
        encoder.stop()
        muxer.stop()
    }

    fun cancelEncoding() {
        encoder.stop()
        muxer.stop()
        File(outputPath).delete()
    }

    companion object {
        const val TAG = "WebMEncoder"
    }
}
