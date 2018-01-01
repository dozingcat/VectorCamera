package com.dozingcatsoftware.boojiecam

import android.renderscript.RenderScript
import com.dozingcatsoftware.boojiecam.effect.Effect
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry
import java.io.RandomAccessFile

/**
 * Created by brian on 12/30/17.
 */
class VideoReader(val rs: RenderScript, val photoLibrary: PhotoLibrary, val videoId: String) {
    val videoFile: RandomAccessFile
    val audioFile: RandomAccessFile?
    val metadata: MediaMetadata
    val effect: Effect

    init {
        videoFile = photoLibrary.rawVideoRandomAccessFileForItemId(videoId)!!
        audioFile = photoLibrary.rawAudioRandomAccessFileForItemId(videoId)
        metadata = photoLibrary.metadataForItemId(videoId)
        effect = EffectRegistry.forMetadata(rs, metadata.effectMetadata)
    }

    fun numberOfFrames(): Int {
        return metadata.frameTimstamps.size
    }

}