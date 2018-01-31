package com.dozingcatsoftware.boojiecam

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.renderscript.RenderScript
import android.util.Log
import com.dozingcatsoftware.boojiecam.effect.EffectMetadata
import com.dozingcatsoftware.util.AndroidUtils
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
 *     images/
 *         [image_id].jpg
 *         [video_id].jpg (if exported)
 *     videos/
 *         [video_id].webm (if exported)
 *     raw_tmp/
 *         [video ID of recording in progress]_video.dat
 *         [video ID of recording in progress]_audio.pcm
 *
 *  "raw_tmp" holds in-progress video recordings, so they can be cleaned up if the recording fails.
 *  Images are stored as flattened YUV data; first (width*height) bytes of Y, then
 *  (width*height/4) bytes of U, then (width*height/4) bytes of V. Video files store individual
 *  frames concatenated together. Audio is mono 16-bit (little-endian) 44kHz PCM.
 */
class PhotoLibrary(val rootDirectory: File) {

    private val thumbnailDirectory = File(rootDirectory, "thumbnails")
    private val metadataDirectory = File(rootDirectory, "metadata")
    private val rawDirectory = File(rootDirectory, "raw")
    private val tempRawDirectory = File(rootDirectory, "tmp_raw")
    private val imageDirectory = File(rootDirectory, "images")
    private val videoDirectory = File(rootDirectory, "videos")
    private val tempVideoDirectory = File(rootDirectory, "tmp_video")

    fun itemIdForTimestamp(timestamp: Long): String = PHOTO_ID_FORMAT.format(Date(timestamp))

    // TODO: Remove successFn and errorFn, just return photo ID and throw exceptions.
    fun savePhoto(context: Context, processedBitmap: ProcessedBitmap,
                  successFn: (String) -> Unit,
                  errorFn: (Exception) -> Unit): String {
        if (processedBitmap.yuvBytes == null &&
                processedBitmap.sourceImage.planarYuvAllocations == null) {
            throw IllegalArgumentException("YUV bytes not set in ProcessedBitmap")
        }
        try {
            Log.i(TAG, "savePhoto start")
            val sourceImage = processedBitmap.sourceImage
            val width = sourceImage.width()
            val height = sourceImage.height()

            val photoId = itemIdForTimestamp(sourceImage.timestamp)

            rawDirectory.mkdirs()
            val rawImageFile = rawImageFileForItemId(photoId)
            // gzip compression usually saves about 50%.
            GZIPOutputStream(FileOutputStream(rawImageFile)).use({
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
            })
            val uncompressedSize = width * height + 2 * (width / 2) * (height / 2)
            val compressedSize = rawImageFile.length()
            val compressedPercent = Math.round(100.0 * compressedSize / uncompressedSize)
            Log.i(TAG, "Wrote $compressedSize bytes, compressed by ${compressedPercent}%")

            val effectMetadata = EffectMetadata(
                    processedBitmap.effect.effectName(), processedBitmap.effect.effectParameters())
            val metadata = MediaMetadata(MediaType.IMAGE, effectMetadata, width, height,
                    sourceImage.orientation, sourceImage.timestamp)
            writeMetadata(metadata, photoId)

            writeImageAndThumbnail(context, processedBitmap, photoId)

            successFn(photoId)
            return photoId
        }
        catch (ex: Exception) {
            errorFn(ex)
            return ""
        }
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
        FileOutputStream(metadataFileForItemId(itemId)).use({
            it.write(json.toByteArray(Charsets.UTF_8))
        })
    }

    fun writeImageAndThumbnail(context: Context, pb: ProcessedBitmap, itemId: String) {
        val resultBitmap = pb.renderBitmap(pb.sourceImage.width(), pb.sourceImage.height())
        imageDirectory.mkdirs()
        val pngFile = imageFileForItemId(itemId)
        val pngOutputStream = FileOutputStream(pngFile)
        pngOutputStream.use({
            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        })
        AndroidUtils.scanSavedMediaFile(context, pngFile.path)
        writeThumbnail(pb, itemId)
    }

    fun writeThumbnail(processedBitmap: ProcessedBitmap, itemId: String) {
        thumbnailDirectory.mkdirs()
        val noMediaFile = File(thumbnailDirectory, ".nomedia")
        if (!noMediaFile.exists()) {
            noMediaFile.createNewFile()
        }
        val thumbnailOutputStream =
                FileOutputStream(File(thumbnailDirectory, itemId + ".jpg"))
        // Preserve aspect ratio?
        val thumbnailBitmap = processedBitmap.renderBitmap(thumbnailWidth, thumbnailHeight)
        thumbnailOutputStream.use({
            thumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        })
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
        return File(tempRawDirectory, itemId + "_video.dat")
    }

    private fun tempRawAudioFileForItemId(itemId: String): File {
        return File(tempRawDirectory, itemId + "_audio.pcm")
    }

    private fun createTempRawFileOutputStream(file: File): OutputStream {
        tempRawDirectory.mkdirs()
        if (file.exists()) {
            throw IllegalStateException("File already exists")
        }
        return FileOutputStream(file)
    }

    fun createTempRawVideoFileOutputStreamForItemId(itemId: String): OutputStream {
        return createTempRawFileOutputStream(tempRawVideoFileForItemId(itemId))
    }

    fun createTempRawAudioFileOutputStreamForItemId(itemId: String): OutputStream {
        return createTempRawFileOutputStream(tempRawAudioFileForItemId(itemId))
    }

    fun clearTempDirectories() {
        tempRawDirectory.deleteRecursively()
        tempVideoDirectory.deleteRecursively()
    }

    fun rawImageFileInputStreamForItemId(itemId: String): InputStream {
        // Backwards compatibility for uncompressed images, remove when no longer needed.
        val file = rawImageFileForItemId(itemId)
        if (file.length() == 1920L * 1080 * 3 / 2) {
            return FileInputStream(rawImageFileForItemId(itemId))
        }
        return GZIPInputStream(FileInputStream(rawImageFileForItemId(itemId)))
    }

    fun saveVideo(context: Context, itemId: String, imageInfo: MediaMetadata,
                  frameTimestamps: List<Long>, audioStartTimestamp: Long) {
        // Move video/audio files from tmp_raw/ to raw/, write metadata.json.
        val videoFile = tempRawVideoFileForItemId(itemId)
        val audioFile = tempRawAudioFileForItemId(itemId)
        if (!videoFile.exists()) {
            throw IllegalStateException("Video file does not exist")
        }
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
        val videoReader = VideoReader(rs, this, itemId, AndroidUtils.displaySize(context))
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
        return File(imageDirectory, itemId + ".jpg")
    }

    fun tempVideoFileForItemIdWithSuffix(itemId: String, suffix: String): File {
        return File(tempVideoDirectory, itemId + ".webm." + suffix)
    }

    fun videoFileForItemId(itemId: String): File {
        return File(videoDirectory, itemId + ".webm")
    }

    fun videoFramesArchiveForItemId(itemId: String): File {
        return File(videoDirectory, itemId + "_frames.zip")
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
                rawAudioFileForItemId(itemId).length())
    }

    fun deleteItem(itemId: String): Boolean {
        // Some or all of these will not exist, which is fine.
        imageFileForItemId(itemId).delete()
        videoFileForItemId(itemId).delete()
        rawImageFileForItemId(itemId).delete()
        rawVideoFileForItemId(itemId).delete()
        rawAudioFileForItemId(itemId).delete()
        return metadataFileForItemId(itemId).delete()
    }

    companion object {
        const val TAG = "PhotoLibrary"
        val PHOTO_ID_FORMAT = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS")
        val thumbnailWidth = 320
        val thumbnailHeight = 240

        init {
            PHOTO_ID_FORMAT.timeZone = TimeZone.getTimeZone("UTC")
        }

        fun defaultLibrary(): PhotoLibrary {
            return PhotoLibrary(
                    File(Environment.getExternalStorageDirectory(), "BoojieCam"))
        }
    }
}

enum class LibraryItemType {IMAGE, VIDEO}
