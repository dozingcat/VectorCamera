package com.dozingcatsoftware.boojiecam

import android.util.Log
import org.json.JSONObject
import org.json.JSONStringer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPOutputStream

/**
 * Created by brian on 10/9/17.
 */
class PhotoLibrary(val rootDirectory: File) {
    val PHOTO_ID_FORMAT = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS")

    init {
        PHOTO_ID_FORMAT.timeZone = TimeZone.getTimeZone("UTC")
    }

    fun savePhoto(processedBitmap: ProcessedBitmap,
                  successFn: (String) -> Unit,
                  errorFn: (Exception) -> Unit) {
        try {
            Log.i(TAG, "savePhoto start")
            val sourceImage = processedBitmap.sourceImage
            val photoId = PHOTO_ID_FORMAT.format(Date(sourceImage.timestamp))
            val photoDir = File(rootDirectory, photoId)
            photoDir.mkdirs()

            val rawImageFile = File(photoDir, "image.gz")
            GZIPOutputStream(FileOutputStream(rawImageFile)).use({
                for (plane in sourceImage.image.planes) {
                    writeBufferToOuptutStream(plane.buffer, it)
                }
            })
            val uncompressedSize = sourceImage.image.width * sourceImage.image.height * 3 / 2
            val compressedSize = rawImageFile.length()
            val compressedPercent = Math.round(100.0 * compressedSize / uncompressedSize)
            Log.i(TAG, "Wrote $compressedSize bytes, compressed to $compressedPercent")

            val metadata = mapOf(
                    "width" to sourceImage.image.width,
                    "height" to sourceImage.image.height,
                    "timestamp" to sourceImage.timestamp
            )
            val json = JSONObject(metadata).toString(2)
            FileOutputStream(File(photoDir, "metadata.json")).use({
                it.write(json.toByteArray(Charsets.UTF_8))
            })

            // TODO: Write full size image and thumbnail.

            successFn(photoId)
        }
        catch (ex: Exception) {
            errorFn(ex)
        }
    }

    companion object {
        val TAG = "PhotoLibrary"
    }
}