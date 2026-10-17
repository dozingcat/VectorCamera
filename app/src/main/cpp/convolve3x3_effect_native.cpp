// Native implementation of Convolve3x3Effect: a 3x3 convolution of the luminance, with edge
// pixels clamped, mapped through a 256-entry color map. Renders straight into the bitmap.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry point so the kernels can be unit tested on
// the host (app/src/test/cpp/convolve3x3_test.cpp).

#include <algorithm>
#include <cstdint>
#include <vector>

#include "pixel_format.h"
#include "worker_pool.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "Convolve3x3EffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace convolve3x3 {

// Convolves one image row into `out` (width bytes). Neighbors outside the image are clamped to
// the edge pixel. The sum is accumulated in the same order as the Kotlin implementation so the
// float rounding matches.
inline void convolveRow(const uint8_t* yData, int width, int height, const float* c, int y,
                        uint8_t* out) {
    const uint8_t* prev = yData + static_cast<size_t>(std::max(y - 1, 0)) * width;
    const uint8_t* curr = yData + static_cast<size_t>(y) * width;
    const uint8_t* next = yData + static_cast<size_t>(std::min(y + 1, height - 1)) * width;
    const float c0 = c[0], c1 = c[1], c2 = c[2], c3 = c[3], c4 = c[4], c5 = c[5],
                c6 = c[6], c7 = c[7], c8 = c[8];
    for (int x = 0; x < width; x++) {
        const int xl = std::max(x - 1, 0);
        const int xr = std::min(x + 1, width - 1);
        const float sum = c0 * prev[xl] + c1 * prev[x] + c2 * prev[xr] +
                          c3 * curr[xl] + c4 * curr[x] + c5 * curr[xr] +
                          c6 * next[xl] + c7 * next[x] + c8 * next[xr];
        const int result = static_cast<int>(sum + 0.5f);
        out[x] = static_cast<uint8_t>(result < 0 ? 0 : (result > 255 ? 255 : result));
    }
}

// Rows [startRow, endRow): convolve each row into a thread-local scratch row, then map it
// through the color map into the output. No full-frame intermediate is needed.
void processRows(const uint8_t* yData, int width, int height, const float* coefficients,
                 const uint32_t* colorMap, uint32_t* pixels, int pixelStride,
                 int startRow, int endRow) {
    static thread_local std::vector<uint8_t> convolvedRow;
    if (convolvedRow.size() < static_cast<size_t>(width)) convolvedRow.resize(width);
    uint8_t* row = convolvedRow.data();
    for (int y = startRow; y < endRow; y++) {
        convolveRow(yData, width, height, coefficients, y, row);
        uint32_t* out = pixels + static_cast<size_t>(y) * pixelStride;
        for (int x = 0; x < width; x++) {
            out[x] = colorMap[row[x]];
        }
    }
}

// Full effect into `pixels` (rows `pixelStride` uint32s apart), writing color map entries as
// given. Row chunks are spread over `numThreads` threads.
void processImage(const uint8_t* yData, int width, int height, const float* coefficients,
                  const uint32_t* colorMap, uint32_t* pixels, int pixelStride, int numThreads) {
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        processRows(yData, width, height, coefficients, colorMap, pixels, pixelStride, start, end);
    });
}

}  // namespace convolve3x3

#ifndef VC_NO_JNI
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_Convolve3x3Effect_00024Companion_processImageNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jint width,
    jint height,
    jfloatArray coefficients,
    jintArray colorMap,
    jobject bitmap,
    jint numThreads
) {
    if (env->GetArrayLength(colorMap) < 256 || env->GetArrayLength(coefficients) < 9) {
        LOGE("Color map or coefficients too short");
        return JNI_FALSE;
    }
    vc::LockedBitmap locked(env, bitmap, width, height);
    if (!locked.ok()) {
        return JNI_FALSE;
    }
    jbyte* yPtr = env->GetByteArrayElements(yData, nullptr);
    jfloat* coeffPtr = env->GetFloatArrayElements(coefficients, nullptr);
    jint* colorMapPtr = env->GetIntArrayElements(colorMap, nullptr);
    if (!yPtr || !coeffPtr || !colorMapPtr) {
        LOGE("Failed to get native arrays");
        if (yPtr) env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
        if (coeffPtr) env->ReleaseFloatArrayElements(coefficients, coeffPtr, JNI_ABORT);
        if (colorMapPtr) env->ReleaseIntArrayElements(colorMap, colorMapPtr, JNI_ABORT);
        return JNI_FALSE;
    }
    // The map holds ARGB color ints; convert once so the per-pixel lookup writes bitmap pixels.
    uint32_t bitmapColorMap[256];
    for (int i = 0; i < 256; i++) {
        bitmapColorMap[i] = vc::argbToRgba(static_cast<uint32_t>(colorMapPtr[i]));
    }
    convolve3x3::processImage(reinterpret_cast<const uint8_t*>(yPtr), width, height, coeffPtr,
                              bitmapColorMap, locked.pixels(), locked.stride(),
                              std::max(1, static_cast<int>(numThreads)));
    env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(coefficients, coeffPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(colorMap, colorMapPtr, JNI_ABORT);
    return JNI_TRUE;
}
#endif
