package com.dozingcatsoftware.vectorcamera

import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import com.dozingcatsoftware.vectorcamera.effect.Effect
import com.dozingcatsoftware.vectorcamera.effect.EffectContext
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry
import java.util.concurrent.Executors

/**
 * Renders thumbnails of effects for the effect picker. Keeps one instance of each effect so that
 * animated effects carry their state between frames. Not thread-safe; callers should use it from
 * a single background thread.
 */
class ThumbnailRenderer(
        private val effectRegistry: EffectRegistry,
        private val prefsFn: (String, Any) -> Any) {

    private val effects = HashMap<String, Effect>()

    private fun effectForId(id: String): Effect {
        return effects.getOrPut(id) {
            effectRegistry.createEffect(id, prefsFn, EffectContext.THUMBNAIL)
        }
    }

    /**
     * Applies the effect with the given ID to `image` and returns a bitmap in display
     * orientation, i.e. rotated and flipped as it would be drawn on screen.
     */
    fun render(id: String, image: CameraImage): Bitmap {
        val pb = effectForId(id).createBitmap(image)
        return pb.renderBitmap(image.width(), image.height())
    }

    companion object {
        /** Shrinks `image` to (at most) `size`, which is a landscape size like the image. */
        fun imageForThumbnails(image: CameraImage, size: Size): CameraImage {
            if (size.width >= image.width() || size.height >= image.height()) {
                return image.copy(displaySize = image.size())
            }
            return image.resizedTo(size).copy(displaySize = size)
        }
    }
}

/**
 * Renders live thumbnails from a stream of camera images. Each call to `processCameraImage`
 * updates as many of the visible effects as fit in `maxMillisPerFrame`, continuing from where
 * the previous frame stopped so that every visible effect gets updated in turn.
 *
 * `visibleIdsFn` and `thumbnailSizeFn` are called on the processing thread; `callback` is
 * called on the processing thread with each rendered thumbnail.
 */
class LiveThumbnailRenderer(
        effectRegistry: EffectRegistry,
        prefsFn: (String, Any) -> Any,
        private val maxMillisPerFrame: Long,
        private val visibleIdsFn: () -> List<String>,
        private val thumbnailSizeFn: () -> Size,
        private val callback: (String, Bitmap) -> Unit,
        private val timeFn: () -> Long = System::currentTimeMillis) {

    private val renderer = ThumbnailRenderer(effectRegistry, prefsFn)
    private var nextId: String? = null

    private var frameCounter = 0;

    fun processCameraImage(cameraImage: CameraImage) {
        val ids = visibleIdsFn()
        if (ids.isEmpty()) {
            return
        }
        val image = ThumbnailRenderer.imageForThumbnails(cameraImage, thumbnailSizeFn())
        val startIndex = Math.max(0, ids.indexOf(nextId))
        val t0 = timeFn()
        var numUpdated = 0
        while (numUpdated < ids.size) {
            val id = ids[(startIndex + numUpdated) % ids.size]
            callback(id, renderer.render(id, image))
            numUpdated += 1
            if (timeFn() - t0 > maxMillisPerFrame) {
                break
            }
        }
        nextId = ids[(startIndex + numUpdated) % ids.size]
        if (frameCounter++ % 30 == 0) {
            Log.i(TAG, "Thumbnail time: ${timeFn() - t0}, updated: $numUpdated of ${ids.size}")
        }
    }

    companion object {
        const val TAG = "LiveThumbnailRenderer"
    }
}

/**
 * Renders thumbnails of a single fixed image on demand, on a background thread. Used for
 * choosing an effect for a saved picture or video frame.
 */
class StaticThumbnailRenderer(
        effectRegistry: EffectRegistry,
        prefsFn: (String, Any) -> Any,
        private val sourceImage: CameraImage,
        private val thumbnailSizeFn: () -> Size,
        private val callback: (String, Bitmap) -> Unit) {

    private val renderer = ThumbnailRenderer(effectRegistry, prefsFn)
    private val executor = Executors.newSingleThreadExecutor()
    private val pendingIds = HashSet<String>()
    // Only accessed from the executor thread.
    private var resizedImage: CameraImage? = null
    private var resizedImageSize: Size? = null

    fun requestThumbnail(id: String) {
        synchronized(pendingIds) {
            if (!pendingIds.add(id)) {
                return
            }
        }
        executor.execute {
            try {
                callback(id, renderer.render(id, imageForSize(thumbnailSizeFn())))
            }
            catch (ex: Exception) {
                Log.w(TAG, "Error rendering thumbnail for $id", ex)
            }
            finally {
                synchronized(pendingIds) { pendingIds.remove(id) }
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun imageForSize(size: Size): CameraImage {
        if (resizedImage == null || resizedImageSize != size) {
            resizedImage = ThumbnailRenderer.imageForThumbnails(sourceImage, size)
            resizedImageSize = size
        }
        return resizedImage!!
    }

    companion object {
        const val TAG = "StaticThumbnailRenderer"
    }
}
