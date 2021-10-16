package com.dozingcatsoftware.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Size
import android.view.WindowManager
import kotlin.math.roundToInt


fun getDisplaySize(context: Context): Size {
    val metrics = DisplayMetrics()
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager.defaultDisplay.getMetrics(metrics)
    return Size(metrics.widthPixels, metrics.heightPixels)
}

fun getLandscapeDisplaySize(context: Context): Size {
    val ds = getDisplaySize(context)
    return if (ds.width >= ds.height) ds else Size(ds.height, ds.width)
}

/**
 * Notifies the OS to index an image so it appears in apps that show media files. Allows optional
 * callback to notify client when the scan is completed, e.g. so it can access the "content" URI
 * that gets assigned.
 */
fun scanSavedMediaFile(context: Context, path: String,
                       callback: ((String, Uri) -> Unit)? = null) {
    var scannerConnection: MediaScannerConnection? = null
    val scannerClient = object : MediaScannerConnection.MediaScannerConnectionClient {
        override fun onMediaScannerConnected() {
            scannerConnection!!.scanFile(path, null)
        }
        override fun onScanCompleted(scanPath: String, scanUri: Uri) {
            scannerConnection!!.disconnect()
            callback?.invoke(scanPath, scanUri)
        }
    }
    scannerConnection = MediaScannerConnection(context, scannerClient)
    scannerConnection.connect()
}

/**
 * Returns a BitmapFactory.Options object containing the size of the image at the given URI,
 * without actually loading the image.
 */
private fun computeBitmapSizeFromURI(context: Context, imageURI: Uri): BitmapFactory.Options {
    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeStream(context.contentResolver.openInputStream(imageURI), null, options)
    return options
}

/**
 * Returns a Bitmap from the given URI that may be scaled by an integer factor to reduce its size,
 * while staying as least as large as the width and height parameters.
 */
fun scaledBitmapFromURIWithMinimumSize(
        context: Context, imageURI: Uri, width: Int, height: Int): Bitmap {
    val options = computeBitmapSizeFromURI(context, imageURI)
    options.inJustDecodeBounds = false

    val wratio = 1.0f * options.outWidth / width
    val hratio = 1.0f * options.outHeight / height
    options.inSampleSize = Math.min(wratio, hratio).toInt()

    return BitmapFactory.decodeStream(
            context.contentResolver.openInputStream(imageURI), null, options)!!
}

/**
 * Returns a Bitmap from the given URI that may be scaled by an integer factor to reduce its size,
 * so that its width and height are no greater than the corresponding parameters. The scale factor
 * will be a power of 2.
 */
fun scaledBitmapFromURIWithMaximumSize(
        context: Context, imageURI: Uri, width: Int, height: Int): Bitmap {
    val options = computeBitmapSizeFromURI(context, imageURI)
    options.inJustDecodeBounds = false

    val wratio = powerOf2GreaterOrEqual(1.0 * options.outWidth / width)
    val hratio = powerOf2GreaterOrEqual(1.0 * options.outHeight / height)
    options.inSampleSize = Math.max(wratio, hratio)

    return BitmapFactory.decodeStream(
            context.contentResolver.openInputStream(imageURI), null, options)!!
}

private fun powerOf2GreaterOrEqual(arg: Double): Int {
    if (arg < 0 && arg > 1 shl 31) throw IllegalArgumentException(arg.toString() + " out of range")
    var result = 1
    while (result < arg) result = result shl 1
    return result
}

/**
 * Returns the maximum size that is a multiple of `size` without being larger than `target`
 * in either dimension. size=[100, 50], target=[300, 200] -> [300, 150]
 */
fun scaleToTargetSize(size: Size, target: Size): Size {
    val ratio = Math.min(
            target.width.toDouble() / size.width, target.height.toDouble() / size.height)
    return Size((size.width * ratio).roundToInt(), (size.height * ratio).roundToInt())
}

// Y/U/V planes for an image.
class YuvImageBuffers(
        val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {

    companion object {
        fun fromBitmap(bitmap: Bitmap, fixedWidth: Int = 0, fixedHeight: Int = 0): YuvImageBuffers {
            if (fixedWidth > bitmap.width || fixedHeight > bitmap.height) {
                throw IllegalArgumentException(
                        "Specified dimensions (${fixedWidth}x${fixedHeight}) exceed bitmap size " +
                        "(${bitmap.width}x${bitmap.height}")
            }
            // Force width and height to be even.
            val baseWidth = if (fixedWidth > 0) fixedWidth else bitmap.width
            val baseHeight = if (fixedHeight > 0) fixedHeight else bitmap.height
            val width = baseWidth - (baseWidth and 1)
            val height = baseHeight - (baseHeight and 1)

            val yBuffer = ByteArray(width * height)
            val uBuffer = ByteArray(width * height / 4)
            val vBuffer = ByteArray(width * height / 4)

            // Read two rows at a time and convert to YUV.
            // https://www.fourcc.org/fccyvrgb.php.
            val pixBuffer = IntArray(2 * width)
            val rb = IntArray(2 * width)
            val gb = IntArray(2 * width)
            val bb = IntArray(2 * width)

            var yIndex = 0
            var uvIndex = 0

            for (r2 in 0 until height / 2) {
                bitmap.getPixels(pixBuffer, 0, width, 0, 2 * r2, width, 2)
                for (i in 0 until pixBuffer.size) {
                    rb[i] = (pixBuffer[i] and 0xff0000) shr 16
                    gb[i] = (pixBuffer[i] and 0x00ff00) shr 8
                    bb[i] = (pixBuffer[i] and 0x0000ff)
                    yBuffer[yIndex++] =
                            clampToUnsignedByte(0.299 * rb[i] + 0.587 * gb[i] + 0.114 * bb[i])
                }
                // For U and V planes, average the pixels in 2x2 blocks.
                for (i in 0 until width / 2) {
                    val x = 2 * i
                    val ra = (rb[x] + rb[x + 1] + rb[x + width] + rb[x + width + 1]) / 4
                    val ga = (gb[x] + gb[x + 1] + gb[x + width] + gb[x + width + 1]) / 4
                    val ba = (bb[x] + rb[x + 1] + bb[x + width] + bb[x + width + 1]) / 4
                    val y = 0.299 * ra + 0.587 * ga + 0.114 * ba
                    uBuffer[uvIndex] = clampToUnsignedByte((ba - y) * 0.565 + 128)
                    vBuffer[uvIndex] = clampToUnsignedByte((ra - y) * 0.713 + 128)
                    uvIndex += 1
                }
            }

            return YuvImageBuffers(width, height, yBuffer, uBuffer, vBuffer)
        }
    }
}

private fun clampToUnsignedByte(v: Double): Byte {
    return (Math.max(0, Math.min(255, v.toInt())) and 0xff).toByte()
}
