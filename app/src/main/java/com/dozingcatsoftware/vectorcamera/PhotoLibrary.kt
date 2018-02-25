package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.renderscript.RenderScript
import android.util.Log
import android.util.Size
import com.dozingcatsoftware.vectorcamera.effect.EffectMetadata
import com.dozingcatsoftware.util.*
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Directory structure:
 * [root]/
 *     thumbnails/
 *         [image_id].jpg
 *         [video_id].jpg
 *     metadata/
 *         [image_id].json
 *         [video_id].json
 *     raw/
 *         [image_id].gz
 *         [video_id]_video.dat
 *         [video_id]_audio.pcm
 *     VectorCamera_images/
 *         [image_id].png
 *     VectorCamera_videos/
 *         [video_id].webm (if exported)
 *     tmp/
 *         [video ID of recording in progress]_video.dat
 *         [video ID of recording in progress]_audio.pcm
 *
 *  "raw_tmp" holds in-progress video recordings, so they can be cleaned up if the recording fails.
 *  Images are stored as flattened YUV data; first (width*height) bytes of Y, then
 *  (width*height/4) bytes of U, then (width*height/4) bytes of V. Video files store individual
 *  frames concatenated together. Audio is mono 16-bit (little-endian) 44kHz PCM.
 *
 *  The images and videos directory have "VectorCamera" prefixes so that they're easier to identify
 *  when using Android's photo picker, which shows only the parent directory name.
 */
class PhotoLibrary(val rootDirectory: File) {

    private val thumbnailDirectory = File(rootDirectory, "thumbnails")
    private val metadataDirectory = File(rootDirectory, "metadata")
    private val rawDirectory = File(rootDirectory, "raw")
    private val imageDirectory = File(rootDirectory, "VectorCamera_images")
    private val videoDirectory = File(rootDirectory, "VectorCamera_videos")
    private val tempDirectory = File(rootDirectory, "tmp")

    fun itemIdForTimestamp(timestamp: Long): String = PHOTO_ID_FORMAT.format(Date(timestamp))

    /**
     * Saves picture data as a compressed bytestream of Y/U/V image planes. Also creates a
     * metadata file and thumbnail image. Does not create a full-size PNG image.
     */
    fun savePhoto(context: Context, processedBitmap: ProcessedBitmap): String {
        val t1 = System.currentTimeMillis()
        if (processedBitmap.yuvBytes == null &&
                processedBitmap.sourceImage.planarYuvAllocations == null) {
            throw IllegalArgumentException("YUV bytes not set in ProcessedBitmap")
        }
        Log.i(TAG, "savePhoto start")
        val sourceImage = processedBitmap.sourceImage
        val width = sourceImage.width()
        val height = sourceImage.height()

        val photoId = itemIdForTimestamp(sourceImage.timestamp)

        rawDirectory.mkdirs()
        val rawImageFile = rawImageFileForItemId(photoId)
        // gzip compression usually saves about 50%.
        val gzipBufferSize = 8192
        val t2 = System.currentTimeMillis()
        writeFileAtomicallyUsingTempDir(rawImageFile, getTempDirectory(), {fos ->
            GZIPOutputStream(fos, gzipBufferSize).use {
                if (processedBitmap.yuvBytes != null) {
                    it.write(processedBitmap.yuvBytes)
                }
                else {
                    val allocBytes = ByteArray(width * height)
                    val planarYuv = processedBitmap.sourceImage.planarYuvAllocations!!
                    planarYuv.y.copyTo(allocBytes)
                    it.write(allocBytes, 0, width * height)
                    planarYuv.u.copyTo(allocBytes)
                    it.write(allocBytes, 0, width * height / 4)
                    planarYuv.v.copyTo(allocBytes)
                    it.write(allocBytes, 0, width * height / 4)
                }
            }
        })
        val t3 = System.currentTimeMillis()
        val uncompressedSize = width * height + 2 * (width / 2) * (height / 2)
        val compressedSize = rawImageFile.length()
        val compressedPercent = Math.round(100.0 * compressedSize / uncompressedSize)
        Log.i(TAG, "Wrote $compressedSize bytes, compressed to ${compressedPercent}%")

        val effectMetadata = EffectMetadata(
                processedBitmap.effect.effectName(), processedBitmap.effect.effectParameters())
        val metadata = MediaMetadata(MediaType.IMAGE, effectMetadata, width, height,
                sourceImage.orientation, sourceImage.timestamp)
        writeMetadata(metadata, photoId)

        writeThumbnail(processedBitmap, photoId)
        val t4 = System.currentTimeMillis()
        Log.i(TAG, "savePhoto times: ${t2-t1} ${t3-t2} ${t4-t3}")
        return photoId
    }

    fun allItemIds(): List<String> {
        val mdFiles = metadataDirectory.listFiles() ?: arrayOf()
        return mdFiles
                .filter({it.name.endsWith(".json")})
                .map({it.name.substring(0, it.name.lastIndexOf('.'))})
    }

    fun writeMetadata(metadata: MediaMetadata, itemId: String) {
        val json = JSONObject(metadata.toJson()).toString(2)
        metadataDirectory.mkdirs()
        writeFileAtomicallyUsingTempDir(metadataFileForItemId(itemId), getTempDirectory(), {
            it.write(json.toByteArray(Charsets.UTF_8))
        })
    }

    /**
     * Writes a full size PNG image to the image directory. This can be slow; calling it on the
     * main thread may block for several seconds.
     * (JPEG would be much faster, but can have noticeably worse quality depending on the effect).
     */
    fun writePngImage(context: Context, pb: ProcessedBitmap, itemId: String) {
        val t1 = System.currentTimeMillis()
        val resultBitmap = pb.renderBitmap(pb.sourceImage.width(), pb.sourceImage.height())
        imageDirectory.mkdirs()
        val imageFile = imageFileForItemId(itemId)
        writeFileAtomicallyUsingTempDir(imageFile, getTempDirectory(), {
            resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        })
        val t2 = System.currentTimeMillis()
        scanSavedMediaFile(context, imageFile.path)
        Log.i(TAG, "writePngImage: ${t2-t1}")
    }

    /**
     * Creates or replaces the thumbnail image for a picture or video.
     */
    fun writeThumbnail(processedBitmap: ProcessedBitmap, itemId: String) {
        thumbnailDirectory.mkdirs()
        val noMediaFile = File(thumbnailDirectory, ".nomedia")
        if (!noMediaFile.exists()) {
            noMediaFile.createNewFile()
        }
        val thumbSize = scaleToTargetSize(processedBitmap.sourceImage.size(), THUMBNAIL_MAX_SIZE)
        // Thumbnails look better if we shrink the input image and then apply the effect, rather
        // than scaling down the image with the effect already applied. Unfortunately this causes
        // intermittent crashes (in native code, no stack trace), possibly due to thread non-safety.
        /*
        Log.i(TAG, "Resizing for thumbnail: ${processedBitmap.sourceImage.size()} -> ${thumbSize}")
        val resizedPB = processedBitmap.resizedTo(thumbSize)
        Log.i(TAG, "Rendering thumbnail")
        val thumbnailBitmap = resizedPB.renderBitmap(thumbSize.width, thumbSize.height)
        Log.i(TAG, "Done")
        */
        val thumbnailBitmap = processedBitmap.renderBitmap(thumbSize.width, thumbSize.height)
        writeFileAtomicallyUsingTempDir(thumbnailFileForItemId(itemId), getTempDirectory(), {
            thumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        })
    }

    fun getTempDirectory(): File {
        tempDirectory.mkdirs()
        return tempDirectory
    }

    fun thumbnailFileForItemId(itemId: String): File {
        return File(thumbnailDirectory, itemId + ".jpg")
    }

    fun rawImageFileForItemId(itemId: String): File {
        return File(rawDirectory, itemId + ".gz")
    }

    fun rawVideoFileForItemId(itemId: String): File {
        return File(rawDirectory, itemId + "_video.dat")
    }

    fun rawAudioFileForItemId(itemId: String): File {
        return File(rawDirectory, itemId + "_audio.pcm")
    }

    private fun tempRawVideoFileForItemId(itemId: String): File {
        return File(tempDirectory, itemId + "_video.dat")
    }

    private fun tempRawAudioFileForItemId(itemId: String): File {
        return File(tempDirectory, itemId + "_audio.pcm")
    }

    fun createTempFileOutputStream(file: File): OutputStream {
        tempDirectory.mkdirs()
        file.delete()
        return FileOutputStream(file)
    }

    fun createTempRawVideoFileOutputStreamForItemId(itemId: String): OutputStream {
        return createTempFileOutputStream(tempRawVideoFileForItemId(itemId))
    }

    fun createTempRawAudioFileOutputStreamForItemId(itemId: String): OutputStream {
        return createTempFileOutputStream(tempRawAudioFileForItemId(itemId))
    }

    fun clearTempDirectories() {
        tempDirectory.deleteRecursively()
    }

    fun rawImageFileInputStreamForItemId(itemId: String): InputStream {
        // Backwards compatibility for uncompressed images, remove when no longer needed.
        val file = rawImageFileForItemId(itemId)
        if (file.length() == 1920L * 1080 * 3 / 2) {
            return FileInputStream(rawImageFileForItemId(itemId))
        }
        return GZIPInputStream(FileInputStream(rawImageFileForItemId(itemId)))
    }

    /**
     * Creates metadata and thumbnail files for a video, and moves the temporary audio and video
     * files to the video directory.
     */
    fun saveVideo(context: Context, itemId: String, imageInfo: MediaMetadata,
                  frameTimestamps: List<Long>, audioStartTimestamp: Long) {
        // Move video/audio files from tmp_raw/ to raw/, write metadata.json.
        val videoFile = tempRawVideoFileForItemId(itemId)
        val audioFile = tempRawAudioFileForItemId(itemId)
        if (!videoFile.exists()) {
            throw IllegalStateException("Video file does not exist")
        }
        rawDirectory.mkdirs()
        videoFile.renameTo(rawVideoFileForItemId(itemId))
        if (audioFile.exists()) {
            audioFile.renameTo(rawAudioFileForItemId(itemId))
        }
        val metadata = MediaMetadata(
                MediaType.VIDEO, imageInfo.effectMetadata, imageInfo.width, imageInfo.height,
                imageInfo.orientation, imageInfo.timestamp, frameTimestamps, audioStartTimestamp)
        writeMetadata(metadata, itemId)
        // Create thumbnail by rendering the first frame.
        // Circular dependency, ick.
        val rs = RenderScript.create(context)
        val videoReader = VideoReader(rs, this, itemId, getDisplaySize(context))
        writeThumbnail(videoReader.bitmapForFrame(0), itemId)
    }

    fun rawVideoRandomAccessFileForItemId(itemId: String): RandomAccessFile? {
        val file = rawVideoFileForItemId(itemId)
        Log.i(TAG, "Raw video file: ${file.path} exists: ${file.exists()}")
        return if (file.isFile) RandomAccessFile(file, "r") else null
    }

    fun rawAudioRandomAccessFileForItemId(itemId: String): RandomAccessFile? {
        val file = rawAudioFileForItemId(itemId)
        return if (file.isFile) RandomAccessFile(file, "r") else null
    }

    fun metadataFileForItemId(itemId: String): File {
        return File(metadataDirectory, itemId + ".json")
    }

    fun imageFileForItemId(itemId: String): File {
        return File(imageDirectory, itemId + ".png")
    }

    fun videoFileForItemId(itemId: String): File {
        return File(videoDirectory, itemId + ".webm")
    }

    fun videoFramesArchiveForItemId(itemId: String): File {
        return File(videoDirectory, itemId + "_frames.zip")
    }

    fun tempFileWithName(filename: String): File {
        return File(tempDirectory, filename)
    }

    fun metadataForItemId(itemId: String): MediaMetadata {
        val mdText = metadataFileForItemId(itemId).readText()
        val mdMap = jsonObjectToMap(JSONObject(mdText))
        return MediaMetadata.fromJson(mdMap)
    }

    fun fileSizeForItemId(itemId: String): Long {
        // length() returns 0 for files that don't exist.
        return (imageFileForItemId(itemId).length() +
                videoFileForItemId(itemId).length() +
                rawImageFileForItemId(itemId).length() +
                rawVideoFileForItemId(itemId).length() +
                rawAudioFileForItemId(itemId).length() +
                videoFramesArchiveForItemId(itemId).length())
    }

    fun deleteItem(itemId: String): Boolean {
        // Some or all of these will not exist, which is fine.
        imageFileForItemId(itemId).delete()
        videoFileForItemId(itemId).delete()
        rawImageFileForItemId(itemId).delete()
        rawVideoFileForItemId(itemId).delete()
        rawAudioFileForItemId(itemId).delete()
        videoFramesArchiveForItemId(itemId).delete()
        return metadataFileForItemId(itemId).delete()
    }

    companion object {
        const val TAG = "PhotoLibrary"
        val PHOTO_ID_FORMAT = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS")
        val THUMBNAIL_MAX_SIZE = Size(480, 360)

        init {
            PHOTO_ID_FORMAT.timeZone = TimeZone.getTimeZone("UTC")
        }

        fun defaultLibrary(): PhotoLibrary {
            return PhotoLibrary(
                    File(Environment.getExternalStorageDirectory(), "VectorCamera"))
        }
    }
}
