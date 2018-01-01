package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import com.dozingcatsoftware.boojiecam.effect.Effect
import com.dozingcatsoftware.boojiecam.effect.EffectMetadata
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
 *         [video_id]_video.gz
 *         [video_id]_audio.gz
 *     images/
 *         [image_id].jpg
 *         [video_id].jpg (if exported)
 *     videos/
 *         [video_id].webm (if exported)
 *
 *  TODO: "raw_tmp" or something to hold in-progress video recordings, so they
 *  can be cleaned up if the recording fails.
 */
class PhotoLibrary(val rootDirectory: File) {

    private val thumbnailDirectory = File(rootDirectory, "thumbnails")
    private val metadataDirectory = File(rootDirectory, "metadata")
    private val rawDirectory = File(rootDirectory, "raw")
    private val tempRawDirectory = File(rootDirectory, "tmp_raw")
    private val imageDirectory = File(rootDirectory, "images")
    private val videoDirectory = File(rootDirectory, "videos")

    fun itemIdForTimestamp(timestamp: Long): String = PHOTO_ID_FORMAT.format(Date(timestamp))

    fun savePhoto(processedBitmap: ProcessedBitmap, yuvBytes: ByteArray,
                  successFn: (String) -> Unit,
                  errorFn: (Exception) -> Unit) {
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
                it.write(yuvBytes)
            })
            val uncompressedSize = width * height + 2 * (width / 2) * (height / 2)
            val compressedSize = rawImageFile.length()
            val compressedPercent = Math.round(100.0 * compressedSize / uncompressedSize)
            Log.i(TAG, "Wrote $compressedSize bytes, compressed to $compressedPercent")

            val effectInfo = mapOf(
                    "name" to processedBitmap.effect.effectName(),
                    "params" to processedBitmap.effect.effectParameters()
            )
            val metadata = mapOf(
                    "type" to "image",
                    "width" to width,
                    "height" to height,
                    "xFlipped" to sourceImage.orientation.isXFlipped(),
                    "yFlipped" to sourceImage.orientation.isYFlipped(),
                    "timestamp" to sourceImage.timestamp,
                    "effect" to effectInfo
            )
            writeMetadata(metadata, photoId)

            // Write full size image and thumbnail.
            // TODO: Scan image with MediaConnectionScanner
            run {
                val resultBitmap = processedBitmap.renderBitmap(width, height)
                imageDirectory.mkdirs()
                val pngOutputStream = FileOutputStream(imageFileForItemId(photoId))
                pngOutputStream.use({
                    resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                })
            }
            run {
                thumbnailDirectory.mkdirs()
                val noMediaFile = File(thumbnailDirectory, ".nomedia")
                if (!noMediaFile.exists()) {
                    noMediaFile.createNewFile()
                }
                val thumbnailOutputStream =
                        FileOutputStream(File(thumbnailDirectory, photoId + ".jpg"))
                // Preserve aspect ratio?
                val thumbnailBitmap = processedBitmap.renderBitmap(thumbnailWidth, thumbnailHeight)
                thumbnailOutputStream.use({
                    thumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                })
            }

            successFn(photoId)
        }
        catch (ex: Exception) {
            errorFn(ex)
        }
    }

    fun allItemIds(): List<String> {
        val mdFiles = metadataDirectory.listFiles() ?: arrayOf()
        return mdFiles
                .filter({it.name.endsWith(".json")})
                .map({it.name.substring(0, it.name.lastIndexOf('.'))})
    }

    fun writeMetadata(metadata: Map<String, Any>, itemId: String) {
        val json = JSONObject(metadata).toString(2)
        metadataDirectory.mkdirs()
        FileOutputStream(metadataFileForItemId(itemId)).use({
            it.write(json.toByteArray(Charsets.UTF_8))
        })
    }

    fun thumbnailFileForItemId(itemId: String): File {
        return File(thumbnailDirectory, itemId + ".jpg")
    }

    fun rawImageFileForItemId(itemId: String): File {
        return File(rawDirectory, itemId + ".gz")
    }

    fun rawVideoFileForItemId(itemId: String): File {
        return File(rawDirectory, itemId + "_video.gz")
    }

    fun rawAudioFileForItemId(itemId: String): File {
        return File(rawDirectory, itemId + "_audio.gz")
    }

    private fun tempRawVideoFileForItemId(itemId: String): File {
        return File(tempRawDirectory, itemId + "_video.gz")
    }

    private fun tempRawAudioFileForItemId(itemId: String): File {
        return File(tempRawDirectory, itemId + "_audio.gz")
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

    fun clearTempRawDirectory() {
        tempRawDirectory.deleteRecursively()
    }

    fun rawImageFileInputStreamForItemId(itemId: String): InputStream {
        // Backwards compatibility for uncompressed images, remove when no longer needed.
        val file = rawImageFileForItemId(itemId)
        if (file.length() == 1920L * 1080 * 3 / 2) {
            return FileInputStream(rawImageFileForItemId(itemId))
        }
        return GZIPInputStream(FileInputStream(rawImageFileForItemId(itemId)))
    }

    fun saveVideo(itemId: String, imageInfo: MediaMetadata, frameTimestamps: List<Long>) {
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
        val effectInfo = mapOf<String, Any>(
                "name" to imageInfo.effectMetadata.name,
                "params" to imageInfo.effectMetadata.parameters
        )
        val metadata = mapOf(
                "type" to "video",
                "width" to imageInfo.width,
                "height" to imageInfo.height,
                "xFlipped" to imageInfo.orientation.isXFlipped(),
                "yFlipped" to imageInfo.orientation.isYFlipped(),
                "timestamp" to imageInfo.timestamp,
                "frameTimestamps" to frameTimestamps,
                "effect" to effectInfo)
        writeMetadata(metadata, itemId)
    }

    fun rawVideoRandomAccessFileForItemId(itemId: String): RandomAccessFile? {
        val file = rawVideoFileForItemId(itemId)
        return if (file == null) null else RandomAccessFile(file, "r")
    }

    fun rawAudioRandomAccessFileForItemId(itemId: String): RandomAccessFile? {
        val file = rawAudioFileForItemId(itemId)
        return if (file == null) null else RandomAccessFile(file, "r")
    }

    fun metadataFileForItemId(itemId: String): File {
        return File(metadataDirectory, itemId + ".json")
    }

    fun imageFileForItemId(itemId: String): File {
        return File(imageDirectory, itemId + ".jpg")
    }

    fun metadataForItemId(itemId: String): MediaMetadata {
        val mdText = metadataFileForItemId(itemId).readText()
        val mdMap = jsonObjectToMap(JSONObject(mdText))
        val effectDict = mdMap["effect"] as Map<String, Any>
        val frameTimestamps = mdMap.getOrElse("frameTimestamps", {listOf<Long>()})
        return MediaMetadata(
                MediaType.forType(mdMap["type"] as String),
                EffectMetadata(
                        effectDict["name"] as String,
                        effectDict["params"] as Map<String, Any>),
                (mdMap["width"] as Number).toInt(),
                (mdMap["height"] as Number).toInt(),
                ImageOrientation.withXYFlipped(
                        mdMap["xFlipped"] as Boolean, mdMap["yFlipped"] as Boolean),
                mdMap["timestamp"] as Long,
                frameTimestamps as List<Long>)

    }

    companion object {
        val TAG = "PhotoLibrary"
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
