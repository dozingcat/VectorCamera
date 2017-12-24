package com.dozingcatsoftware.boojiecam

import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
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
 */
class PhotoLibrary(val rootDirectory: File) {

    private val thumbnailDirectory = File(rootDirectory, "thumbnails")
    private val metadataDirectory = File(rootDirectory, "metadata")
    private val rawDirectory = File(rootDirectory, "raw")
    private val imageDirectory = File(rootDirectory, "images")
    private val videoDirectory = File(rootDirectory, "videos")

    fun savePhoto(processedBitmap: ProcessedBitmap, yuvBytes: ByteArray,
                  successFn: (String) -> Unit,
                  errorFn: (Exception) -> Unit) {
        try {
            Log.i(TAG, "savePhoto start")
            val sourceImage = processedBitmap.sourceImage
            val width = sourceImage.width()
            val height = sourceImage.height()

            val photoId = PHOTO_ID_FORMAT.format(Date(sourceImage.timestamp))

            rawDirectory.mkdirs()
            val rawImageFile = rawFileForItemId(photoId)
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
            val json = JSONObject(metadata).toString(2)
            metadataDirectory.mkdirs()
            FileOutputStream(metadataFileForItemId(photoId)).use({
                it.write(json.toByteArray(Charsets.UTF_8))
            })

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

    fun thumbnailFileForItemId(itemId: String): File {
        return File(thumbnailDirectory, itemId + ".jpg")
    }

    fun rawFileForItemId(itemId: String): File {
        return File(rawDirectory, itemId + ".gz")
    }

    fun createRawFileOutputStreamForItemId(itemId: String): OutputStream {
        val file = rawFileForItemId(itemId)
        if (file.exists()) {
            throw IllegalStateException("File already exists")
        }
        rawDirectory.mkdirs()
        return FileOutputStream(file)
    }

    fun rawFileInputStreamForItemId(itemId: String): InputStream {
        // Backwards compatibility for uncompressed images, remove when no longer needed.
        val file = rawFileForItemId(itemId)
        if (file.length() == 1920L * 1080 * 3 / 2) {
            return FileInputStream(rawFileForItemId(itemId))
        }
        return GZIPInputStream(FileInputStream(rawFileForItemId(itemId)))
    }

    fun metadataFileForItemId(itemId: String): File {
        return File(metadataDirectory, itemId + ".json")
    }

    fun imageFileForItemId(itemId: String): File {
        return File(imageDirectory, itemId + ".jpg")
    }

    fun metadataForItemId(itemId: String): Map<String, Any> {
        val mdText = metadataFileForItemId(itemId).readText()
        return jsonObjectToMap(JSONObject(mdText))
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
