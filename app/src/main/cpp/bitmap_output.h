// Direct rendering into android.graphics.Bitmap pixel buffers from JNI code.
#ifndef VECTORCAMERA_BITMAP_OUTPUT_H
#define VECTORCAMERA_BITMAP_OUTPUT_H

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <cstdint>

namespace vc {

// Locks a Bitmap's pixels for the lifetime of the object. Effects write pixels in the layout
// described in pixel_format.h, skipping the Java pixel array and Bitmap.setPixels copy that
// otherwise cost more than many effects themselves at camera resolution.
//
// Usage:
//   LockedBitmap bitmap(env, jbitmap, width, height);
//   if (!bitmap.ok()) return JNI_FALSE;   // Kotlin then falls back to the pixel array path.
//   ... write bitmap.pixels(), rows bitmap.stride() uint32s apart ...
class LockedBitmap {
public:
    // Fails, leaving the bitmap untouched, unless it is RGBA_8888 (Bitmap.Config.ARGB_8888) and
    // exactly width x height.
    LockedBitmap(JNIEnv* env, jobject bitmap, int width, int height)
        : env_(env), bitmap_(bitmap) {
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            static_cast<int>(info.width) != width || static_cast<int>(info.height) != height ||
            info.stride % 4 != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "LockedBitmap",
                                "Bitmap is not a %dx%d RGBA_8888 bitmap", width, height);
            return;
        }
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            __android_log_print(ANDROID_LOG_ERROR, "LockedBitmap", "Failed to lock bitmap pixels");
            return;
        }
        pixels_ = static_cast<uint32_t*>(pixels);
        stride_ = static_cast<int>(info.stride / 4);
    }

    ~LockedBitmap() {
        if (pixels_ != nullptr) {
            AndroidBitmap_unlockPixels(env_, bitmap_);
        }
    }

    LockedBitmap(const LockedBitmap&) = delete;
    LockedBitmap& operator=(const LockedBitmap&) = delete;

    bool ok() const { return pixels_ != nullptr; }
    uint32_t* pixels() const { return pixels_; }
    // Distance between rows in uint32 pixels; may exceed the width.
    int stride() const { return stride_; }
    uint32_t* row(int y) const { return pixels_ + static_cast<size_t>(y) * stride_; }

private:
    JNIEnv* env_;
    jobject bitmap_;
    uint32_t* pixels_ = nullptr;
    int stride_ = 0;
};

}  // namespace vc

#endif  // VECTORCAMERA_BITMAP_OUTPUT_H
