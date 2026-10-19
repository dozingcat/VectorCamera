// Native implementation of ColorMapEffect: each pixel's luminance (solid mode) or Laplacian edge
// strength (edge mode) indexes a 256-entry color map. Renders straight into the bitmap.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry point so the kernels can be unit tested on
// the host (app/src/test/cpp/effects_test.cpp).

#include <algorithm>
#include <cstdint>
#include <vector>

#include "pixel_format.h"
#include "worker_pool.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "ColorMapEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace colormap {

// Solid mode for rows [startRow, endRow): the luminance is the color map index.
void processRowsSolid(const uint8_t* yData, int width, const uint32_t* colorMap,
                      uint32_t* pixels, int pixelStride, int startRow, int endRow) {
    for (int y = startRow; y < endRow; y++) {
        const uint8_t* row = yData + static_cast<size_t>(y) * width;
        uint32_t* out = pixels + static_cast<size_t>(y) * pixelStride;
        for (int x = 0; x < width; x++) {
            out[x] = colorMap[row[x]];
        }
    }
}

// Edge mode for rows [startRow, endRow): the Laplacian edge strength, scaled by `multiplier`
// and clamped to 0-255, is the color map index. Border pixels have edge strength 0.
void processRowsEdge(const uint8_t* yData, int width, int height, int multiplier,
                     const uint32_t* colorMap, uint32_t* pixels, int pixelStride,
                     int startRow, int endRow) {
    for (int y = startRow; y < endRow; y++) {
        uint32_t* out = pixels + static_cast<size_t>(y) * pixelStride;
        if (y == 0 || y == height - 1 || width < 3) {
            std::fill(out, out + width, colorMap[0]);
            continue;
        }
        const uint8_t* prev = yData + static_cast<size_t>(y - 1) * width;
        const uint8_t* curr = prev + width;
        const uint8_t* next = curr + width;
        out[0] = colorMap[0];
        for (int x = 1; x < width - 1; x++) {
            const int surrounding = prev[x - 1] + prev[x] + prev[x + 1] +
                                    curr[x - 1] + curr[x + 1] +
                                    next[x - 1] + next[x] + next[x + 1];
            const int edge = multiplier * (8 * curr[x] - surrounding);
            out[x] = colorMap[std::max(0, std::min(edge, 255))];
        }
        out[width - 1] = colorMap[0];
    }
}

// Full effect into `pixels` (rows `pixelStride` uint32s apart), writing color map entries as
// given. Row chunks are spread over `numThreads` threads.
void processImage(const uint8_t* yData, int width, int height, bool edgeMode, int multiplier,
                  const uint32_t* colorMap, uint32_t* pixels, int pixelStride, int numThreads) {
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        if (edgeMode) {
            processRowsEdge(yData, width, height, multiplier, colorMap, pixels, pixelStride,
                            start, end);
        } else {
            processRowsSolid(yData, width, colorMap, pixels, pixelStride, start, end);
        }
    });
}

}  // namespace colormap

#ifndef VC_NO_JNI
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_ColorMapEffect_00024Companion_processImageNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jint width,
    jint height,
    jboolean edgeMode,
    jint multiplier,
    jintArray colorMap,
    jobject bitmap,
    jint numThreads
) {
    if (env->GetArrayLength(colorMap) < 256) {
        LOGE("Color map has fewer than 256 entries");
        return JNI_FALSE;
    }
    vc::LockedBitmap locked(env, bitmap, width, height);
    if (!locked.ok()) {
        return JNI_FALSE;
    }
    jbyte* yDataPtr = env->GetByteArrayElements(yData, nullptr);
    jint* colorMapPtr = env->GetIntArrayElements(colorMap, nullptr);
    if (!yDataPtr || !colorMapPtr) {
        LOGE("Failed to get native arrays");
        if (yDataPtr) env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
        if (colorMapPtr) env->ReleaseIntArrayElements(colorMap, colorMapPtr, JNI_ABORT);
        return JNI_FALSE;
    }
    // The map holds ARGB color ints; convert once so the per-pixel lookup writes bitmap pixels.
    uint32_t bitmapColorMap[256];
    for (int i = 0; i < 256; i++) {
        bitmapColorMap[i] = vc::argbToRgba(static_cast<uint32_t>(colorMapPtr[i]));
    }
    colormap::processImage(reinterpret_cast<const uint8_t*>(yDataPtr), width, height,
                           edgeMode == JNI_TRUE, multiplier, bitmapColorMap, locked.pixels(),
                           locked.stride(), std::max(1, static_cast<int>(numThreads)));
    env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(colorMap, colorMapPtr, JNI_ABORT);
    return JNI_TRUE;
}
#endif
