package com.dozingcatsoftware.vectorcamera


import android.util.Size
import com.dozingcatsoftware.vectorcamera.effect.Effect
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

// Maybe get rid of PhotoLibrary parameter and pass files/metadata as individual arguments.
class VideoReader(photoLibrary: PhotoLibrary, videoId: String,
                  private val displaySize: Size) {
    private val videoFile = photoLibrary.rawVideoRandomAccessFileForItemId(videoId)!!
    private val metadata = photoLibrary.metadataForItemId(videoId)
    private val frameBuffer: ByteArray
    // effect can be changed after creation.
    var effect: Effect

    init {
        // It would be better to pass in the EffectRegistry.
        effect = EffectRegistry().effectForMetadata(metadata.effectMetadata)
        frameBuffer = ByteArray(bytesPerFrame())
    }

    fun isPortrait() = metadata.orientation.portrait
    fun landscapeVideoWidth() = metadata.width
    fun landscapeVideoHeight() = metadata.height
    fun outputVideoWidth() = if (isPortrait()) metadata.height else metadata.width
    fun outputVideoHeight() = if (isPortrait()) metadata.width else metadata.height
    fun numberOfFrames() = metadata.frameTimestamps.size

    private fun bytesPerFrame() = metadata.width * metadata.height * 3 / 2

    /** Reads the raw frame at `frameIndex` without applying an effect. */
    fun cameraImageForFrame(frameIndex: Int): CameraImage {
        if (frameIndex < 0 || frameIndex >= numberOfFrames()) {
            throw IllegalArgumentException("Invalid frame index: ${frameIndex}")
        }
        val bpf = bytesPerFrame().toLong()
        videoFile.seek(frameIndex * bpf)
        videoFile.readFully(frameBuffer)
        val imageData = ImageData.fromYuvBytes(frameBuffer, metadata.width, metadata.height)
        return CameraImage(
                imageData, metadata.orientation, CameraStatus.CAPTURING_VIDEO,
                metadata.frameTimestamps[frameIndex], displaySize)
    }

    fun bitmapForFrame(frameIndex: Int): ProcessedBitmap {
        return effect.createBitmap(cameraImageForFrame(frameIndex))
    }

    fun millisBetweenFrames(frame1Index: Int, frame2Index: Int): Long {
        val timestamps = metadata.frameTimestamps
        return Math.abs(timestamps[frame2Index] - timestamps[frame1Index])
    }

    fun averageFrameDurationMillis(): Long {
        val timestamps = metadata.frameTimestamps
        return (timestamps.last() - timestamps.first()) / (numberOfFrames() - 1)
    }

    // Assume the last frame has a duration equal to the average duration of the other frames.
    fun frameDurationMillis(frameIndex: Int): Long {
        if (frameIndex == numberOfFrames() - 1) {
            return averageFrameDurationMillis()
        }
        if (frameIndex >= 0 && frameIndex < numberOfFrames() - 1) {
            return millisBetweenFrames(frameIndex, frameIndex + 1)
        }
        throw IllegalArgumentException("Bad frame index: ${frameIndex}")
    }

    fun totalDurationMillis(): Long {
        val timestamps = metadata.frameTimestamps
        return (timestamps.last() - timestamps.first()) + averageFrameDurationMillis()
    }

    fun nextFrameIndexForTimeDelta(baseFrameIndex: Int, targetDeltaMillis: Long): Int {
        var index = baseFrameIndex
        val maxIndex = numberOfFrames() - 1
        while (true) {
            if (index >= maxIndex) {
                return maxIndex
            }
            if (millisBetweenFrames(baseFrameIndex, index) > targetDeltaMillis) {
                return index
            }
            index += 1
        }
    }
}
