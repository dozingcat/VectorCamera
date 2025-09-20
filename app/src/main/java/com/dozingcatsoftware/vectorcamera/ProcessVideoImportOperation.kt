package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry
import com.dozingcatsoftware.util.YuvImageBuffers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.scale

/**
 * Imports an existing video into VectorCamera's raw storage format.
 * - Downscales frames uniformly so that max(dstW, dstH) = 640 (even dims enforced)
 * - Targets 30 fps (caps to source fps if lower)
 * - Extracts/decodes audio to mono 44.1kHz 16-bit PCM
 */
class ProcessVideoImportOperation(val timeFn: (() -> Long) = System::currentTimeMillis) {

    data class Preflight(
            val srcWidth: Int,
            val srcHeight: Int,
            val rotation: Int,
            val durationMs: Long,
            val sourceFps: Float,
            val targetFps: Float,
            val dstWidth: Int,
            val dstHeight: Int,
            val estimatedFrames: Int,
            val estimatedTotalBytes: Long) {
        val estimatedTotalMB: Double get() = estimatedTotalBytes / 1_000_000.0
    }

    fun preflight(context: Context, uri: Uri): Preflight {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationMs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?: "0").toLong()
        val rotation = (retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "0").toInt()
        val srcW = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?: "0").toInt()
        val srcH = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?: "0").toInt()
        retriever.release()

        val sourceFps = estimateSourceFps(context, uri)
        val targetFps = min(30f, max(1f, sourceFps))

        val maxSrcDim = max(srcW, srcH).coerceAtLeast(1)
        val scale = 640.0 / maxSrcDim
        var dstW = (srcW * scale).roundToInt()
        var dstH = (srcH * scale).roundToInt()
        // Force even dimensions for YUV420
        if (dstW % 2 == 1) dstW += 1
        if (dstH % 2 == 1) dstH += 1

        val estimatedFrames = ceil((durationMs / 1000.0) * targetFps).toInt()
        val videoBytesPerFrame = dstW * dstH * 3 / 2
        val estimatedVideoBytes = estimatedFrames.toLong() * videoBytesPerFrame
        val estimatedAudioBytes = (88200.0 * (durationMs / 1000.0)).toLong() // mono 44.1kHz 16-bit
        val totalBytes = estimatedVideoBytes + estimatedAudioBytes

        return Preflight(srcW, srcH, rotation, durationMs, sourceFps, targetFps,
                dstW, dstH, estimatedFrames, totalBytes)
    }

    fun process(context: Context, uri: Uri,
                dstWidth: Int, dstHeight: Int, targetFps: Float,
                progressHandler: ((processedFrames: Int, totalFrames: Int) -> Unit)? = null,
                cancelChecker: (() -> Boolean)? = null): String {
        val photoLibrary = PhotoLibrary.defaultLibrary(context)
        val videoId = photoLibrary.itemIdForTimestamp(timeFn())

        // Open output files in temp dir
        val videoOut = photoLibrary.createTempRawVideoFileOutputStreamForItemId(videoId)
        val audioOut = photoLibrary.createTempRawAudioFileOutputStreamForItemId(videoId)

        val frameTimestampsMs = mutableListOf<Long>()

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationMs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?: "0").toLong()
        val rotation = (retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "0").toInt()

        // Decode video frames using MediaMetadataRetriever at fixed time steps
        val frameIntervalUs = (1_000_000.0 / targetFps).toLong()
        val totalFrames = ceil((durationMs / 1000.0) * targetFps).toInt()
        var timeUs = 0L
        var frameIndex = 0
        while (timeUs <= durationMs * 1000) {
            if (cancelChecker?.invoke() == true) {
                throw InterruptedException("Video import cancelled")
            }
            val bmp: Bitmap? = try {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (ex: Exception) {
                null
            }
            if (bmp != null) {
                // Apply rotation to make stored frames landscape if source is portrait
                val portrait = (rotation % 180 != 0)
                val rotated = if (portrait) {
                    val m = android.graphics.Matrix()
                    m.postRotate(-rotation.toFloat())
                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                } else bmp
                val landscapeW = max(dstWidth, dstHeight)
                val landscapeH = min(dstWidth, dstHeight)
                val scaled = rotated.scale(landscapeW, landscapeH)
                val yuv = YuvImageBuffers.fromBitmap(scaled, landscapeW, landscapeH)
                // Write Y, then U, then V
                videoOut.write(yuv.y)
                videoOut.write(yuv.u)
                videoOut.write(yuv.v)
                // Timestamp in ms based on requested time
                frameTimestampsMs.add(timeUs / 1000)
                if (scaled !== rotated) scaled.recycle()
                if (rotated !== bmp) rotated.recycle()
                bmp.recycle()
                frameIndex += 1
                progressHandler?.invoke(frameIndex, totalFrames)
            }
            timeUs += frameIntervalUs
        }
        retriever.release()

        // Decode audio to PCM 16-bit mono 44.1kHz
        var audioStartTimestampMs = 0L
        try {
            audioStartTimestampMs = decodeAudioToPcm(context, uri, audioOut, cancelChecker)
        } catch (ex: Exception) {
            Log.w(TAG, "Audio decode failed, continuing without audio", ex)
        } finally {
            try { audioOut.close() } catch (_: Exception) {}
        }

        try { videoOut.close() } catch (_: Exception) {}

        // Orientation and effect metadata
        val portrait = (rotation % 180 != 0)
        val orientation = ImageOrientation.NORMAL.withPortrait(portrait)
        val prefs = VCPreferences(context)
        val effect = prefs.effect({ EffectRegistry().defaultEffectAtIndex(0, prefs.lookupFunction) })
        val effectMetadata = effect.effectMetadata()

        val md = MediaMetadata(
                MediaType.VIDEO, effectMetadata,
                // Store actual stored frame dimensions (landscape)
                max(dstWidth, dstHeight),
                min(dstWidth, dstHeight),
                orientation, timeFn())

        PhotoLibrary.defaultLibrary(context).saveVideo(
                context, videoId, md, frameTimestampsMs, audioStartTimestampMs)

        return videoId
    }

    // Attempts to estimate FPS by sampling a short segment of the video track.
    private fun estimateSourceFps(context: Context, uri: Uri): Float {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                mime.startsWith("video/")
            } ?: return 30f
            extractor.selectTrack(trackIndex)
            var frames = 0
            var firstPts = -1L
            var lastPts = -1L
            val buffer = ByteBuffer.allocate(1 shl 20)
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val pts = extractor.sampleTime
                if (firstPts < 0) firstPts = pts
                lastPts = pts
                frames += 1
                extractor.advance()
                if (frames >= 180) break // ~6 seconds at 30fps
            }
            if (frames > 1 && lastPts > firstPts) {
                val durSec = (lastPts - firstPts) / 1_000_000.0
                (frames - 1) / durSec.toFloat()
            } else 30f
        } catch (ex: Exception) {
            Log.w(TAG, "FPS estimate failed", ex)
            30f
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    // Returns first audio PTS relative to video start in ms; writes PCM 16-bit mono 44.1kHz
    private fun decodeAudioToPcm(context: Context, uri: Uri, audioOut: java.io.OutputStream,
                                 cancelChecker: (() -> Boolean)? = null): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            mime.startsWith("audio/")
        } ?: run {
            extractor.release()
            return 0L
        }
        val videoTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            mime.startsWith("video/")
        }

        val audioFormat = extractor.getTrackFormat(audioTrackIndex)
        val audioMime = audioFormat.getString(MediaFormat.KEY_MIME)!!
        extractor.selectTrack(audioTrackIndex)

        val decoder = MediaCodec.createDecoderByType(audioMime)
        decoder.configure(audioFormat, null, null, 0)
        decoder.start()

        val inputBuffers = decoder.inputBuffers
        val outputBuffers = decoder.outputBuffers
        val info = MediaCodec.BufferInfo()

        var inputDone = false
        var firstAudioPtsUs = -1L
        var firstVideoPtsUs = -1L
        if (videoTrackIndex != null) {
            val vfmt = extractor.getTrackFormat(videoTrackIndex)
            // Try to find first video pts by peeking
            val vextractor = MediaExtractor()
            try {
                vextractor.setDataSource(context, uri, null)
                vextractor.selectTrack(videoTrackIndex)
                val buf = ByteBuffer.allocate(1 shl 16)
                if (vextractor.readSampleData(buf, 0) >= 0) {
                    firstVideoPtsUs = vextractor.sampleTime
                }
            } catch (_: Exception) {
            } finally {
                try { vextractor.release() } catch (_: Exception) {}
            }
        }

        val srcSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        while (true) {
            if (cancelChecker?.invoke() == true) {
                break
            }
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = inputBuffers[inIndex]
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val pts = extractor.sampleTime
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val outBuf = outputBuffers[outIndex]
                if (info.size > 0) {
                    if (firstAudioPtsUs < 0) firstAudioPtsUs = info.presentationTimeUs
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frame = ShortArray(shorts.remaining())
                    shorts.get(frame)
                    // Downmix to mono if needed
                    val mono: ShortArray = if (srcChannels <= 1) frame else {
                        val out = ShortArray(frame.size / srcChannels)
                        var si = 0
                        var di = 0
                        while (si + srcChannels - 1 < frame.size) {
                            var acc = 0
                            for (c in 0 until srcChannels) acc += frame[si + c].toInt()
                            out[di] = (acc / srcChannels).toShort()
                            si += srcChannels
                            di += 1
                        }
                        out
                    }
                    // Resample to 44100 if needed
                    val resampled: ShortArray = if (srcSampleRate == 44100) mono else {
                        resampleLinear(mono, srcSampleRate, 44100)
                    }
                    // Write
                    val b = ByteArray(resampled.size * 2)
                    var bi = 0
                    for (s in resampled) {
                        b[bi++] = (s.toInt() and 0xFF).toByte()
                        b[bi++] = ((s.toInt() shr 8) and 0xFF).toByte()
                    }
                    audioOut.write(b)
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // Deprecated but handle for older API
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Ignored
            } else {
                // no output
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        val audioStartMs = if (firstAudioPtsUs >= 0 && firstVideoPtsUs >= 0)
            max(0L, (firstAudioPtsUs - firstVideoPtsUs) / 1000) else 0L
        return audioStartMs
    }

    private fun resampleLinear(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (input.isEmpty()) return input
        val ratio = dstRate.toDouble() / srcRate
        val outLen = max(1, (input.size * ratio).roundToInt())
        val out = ShortArray(outLen)
        var pos = 0.0
        for (i in 0 until outLen) {
            val idx = pos.toInt().coerceAtMost(input.size - 1)
            val frac = pos - idx
            val a = input[idx].toInt()
            val b = input[min(idx + 1, input.size - 1)].toInt()
            val v = (a * (1 - frac) + b * frac).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = v.toShort()
            pos += 1.0 / ratio
        }
        return out
    }

    companion object {
        const val TAG = "ProcessVideoImportOperation"
    }
}


