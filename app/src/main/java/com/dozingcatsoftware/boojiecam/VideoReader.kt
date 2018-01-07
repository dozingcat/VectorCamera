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
    private val audioFile: RandomAccessFile?
    private val metadata: MediaMetadata
    private val frameBuffer: ByteArray
    // effect and displaySize can be changed after creation
    var effect: Effect

    init {
        videoFile = photoLibrary.rawVideoRandomAccessFileForItemId(videoId)!!
        audioFile = photoLibrary.rawAudioRandomAccessFileForItemId(videoId)
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
        val cameraImage = CameraImage(
                null, allocation, metadata.orientation, CameraStatus.CAPTURING_VIDEO,
                0L, displaySize)
        return ProcessedBitmap(effect, cameraImage,
                effect.createBitmap(cameraImage), effect.createPaintFn(cameraImage))
    }
}