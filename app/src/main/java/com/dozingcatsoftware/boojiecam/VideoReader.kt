package com.dozingcatsoftware.boojiecam

import android.renderscript.RenderScript
import android.util.Size
import com.dozingcatsoftware.boojiecam.effect.Effect
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile

// Maybe get rid of PhotoLibrary parameter and pass files/metadata as individual arguments.
class VideoReader(val rs: RenderScript, val photoLibrary: PhotoLibrary, val videoId: String,
                  var displaySize: Size) {
    private val videoFile: RandomAccessFile
    private val metadata: MediaMetadata
    private val frameBuffer: ByteArray
    // effect and displaySize can be changed after creation
    var effect: Effect

    init {
        videoFile = photoLibrary.rawVideoRandomAccessFileForItemId(videoId)!!
        metadata = photoLibrary.metadataForItemId(videoId)
        effect = EffectRegistry.forMetadata(rs, metadata.effectMetadata)
        frameBuffer = ByteArray(bytesPerFrame())
    }

    fun numberOfFrames(): Int {
        return metadata.frameTimestamps.size
    }

    private fun bytesPerFrame() = metadata.width * metadata.height * 3 / 2

    fun bitmapForFrame(frameIndex: Int): ProcessedBitmap {
        if (frameIndex < 0 || frameIndex >= numberOfFrames()) {
            throw IllegalArgumentException("Invalid frame index: ${frameIndex}")
        }
        val bpf = bytesPerFrame().toLong()
        videoFile.seek(frameIndex * bpf)
        videoFile.readFully(frameBuffer)
        val allocation = PlanarYuvAllocations.fromInputStream(
                rs, ByteArrayInputStream(frameBuffer), metadata.width, metadata.height)
        val cameraImage = CameraImage.withAllocationSet(
                allocation, metadata.orientation, CameraStatus.CAPTURING_VIDEO,
                0L, displaySize)
        return ProcessedBitmap(effect, cameraImage,
                effect.createBitmap(cameraImage), effect.createPaintFn(cameraImage))
    }

    fun millisBetweenFrames(frame1Index: Int, frame2Index: Int): Long {
        val timestamps = metadata.frameTimestamps
        return Math.abs(timestamps[frame2Index] - timestamps[frame1Index])
    }

    fun nextFrameIndexForTimeDelta(baseFrameIndex: Int, targetDeltaMillis: Long): Int {
        var index = baseFrameIndex
        val maxIndex = numberOfFrames() - 1
        val timestamps = metadata.frameTimestamps
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
