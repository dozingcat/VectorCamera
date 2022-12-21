package com.dozingcatsoftware.vectorcamera

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

import com.dozingcatsoftware.util.YuvImageBuffers

/**
 * Encodes a series of video frames to a WebM file using the MedicCodec and MediaMuxer APIs.
 */
class WebMEncoder(private val videoReader: VideoReader, private val outputPath: String) {

    private val numFrames = videoReader.numberOfFrames()
    private val outputWidth = videoReader.outputVideoWidth()
    private val outputHeight = videoReader.outputVideoHeight()
    // U and V planes each have 1/4 the number of samples as the number of pixels.
    private val bytesPerFrame = outputWidth * outputHeight * 3 / 2

    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var videoTrackIndex = -1
    private lateinit var frameRelativeEndTimes: IntArray

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
        if (inputBufferId < 0) {
            throw RuntimeException("Can't dequeue input buffer")
        }
        val inputImage: Image = encoder.getInputImage(inputBufferId)
                ?: throw NullPointerException("Input buffer is null")
        if (inputImage.format != ImageFormat.YUV_420_888) {
            throw RuntimeException("Unexpected encoder image format: ${inputImage.format}")
        }

        // Render a bitmap with the selected effect, extract the pixels, and send to the encoder.
        val bitmap = videoReader.bitmapForFrame(frameIndex).renderBitmap(
                videoReader.landscapeVideoWidth(), videoReader.landscapeVideoHeight())

        val yuvBuffers = YuvImageBuffers.fromBitmap(bitmap, outputWidth, outputHeight)
        fillEncoderImageFromYuvBuffers(inputImage, yuvBuffers)

        val offsetMicros = if (frameIndex == 0) 0 else frameRelativeEndTimes[frameIndex - 1] * 1000L
        val flags = if (frameIndex == numFrames - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        encoder.queueInputBuffer(inputBufferId, 0, bytesPerFrame, offsetMicros, flags)
    }

    private fun fillEncoderImageFromYuvBuffers(image: Image, yuv: YuvImageBuffers) {
        // https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888
        // We can fill the Y plane rows directly because it's guaranteed to not overlap U/V and have
        // a pixel stride of 1. U and V may be interleaved so we have to fill them a byte at a time.
        val planes = image.planes
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        val yBuffer = planes[0].buffer
        for (row in 0 until yuv.height) {
            yBuffer.position(yRowStride * row)
            yBuffer.put(yuv.y, yuv.width * row, yuv.width)
        }

        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        var uvIndex = 0
        for (row in 0 until yuv.height / 2) {
            val rowOffset = uvRowStride * row
            for (col in 0 until yuv.width / 2) {
                val offset = rowOffset + col * uvPixelStride
                uBuffer.put(offset, yuv.u[uvIndex])
                vBuffer.put(offset, yuv.v[uvIndex])
                uvIndex += 1
            }
        }
    }

    private fun writeEncoderOutput() {
        val bufferInfo = MediaCodec.BufferInfo()
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
