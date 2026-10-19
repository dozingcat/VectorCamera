// Native implementation of EdgeLuminanceEffect: each pixel keeps its chroma (U, V) but its
// brightness is replaced by its Laplacian edge strength. Renders straight into the bitmap.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry point so the kernels can be unit tested on
// the host (app/src/test/cpp/effects_test.cpp).

#include <algorithm>
#include <cstdint>

#include "worker_pool.h"
#include "yuv.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "EdgeLuminanceNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace edge_luminance {

// Rows [startRow, endRow): edge strength (0 on the image border) as Y with the pixel's own U, V.
void processRows(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                 int height, int uvWidth, int multiplier, uint32_t* pixels, int pixelStride,
                 int startRow, int endRow) {
    for (int y = startRow; y < endRow; y++) {
        const uint8_t* uRow = uData + static_cast<size_t>(y / 2) * uvWidth;
        const uint8_t* vRow = vData + static_cast<size_t>(y / 2) * uvWidth;
        uint32_t* out = pixels + static_cast<size_t>(y) * pixelStride;
        const bool interiorRow = y > 0 && y < height - 1;
        const uint8_t* prev = yData + static_cast<size_t>(std::max(y - 1, 0)) * width;
        const uint8_t* curr = yData + static_cast<size_t>(y) * width;
        const uint8_t* next = yData + static_cast<size_t>(std::min(y + 1, height - 1)) * width;
        for (int x = 0; x < width; x++) {
            int edge = 0;
            if (interiorRow && x > 0 && x < width - 1) {
                const int surrounding = prev[x - 1] + prev[x] + prev[x + 1] +
                                        curr[x - 1] + curr[x + 1] +
                                        next[x - 1] + next[x] + next[x + 1];
                edge = std::max(0, std::min(multiplier * (8 * curr[x] - surrounding), 255));
            }
            out[x] = YuvUtils::yuvToRgbaFixed(edge, uRow[x / 2], vRow[x / 2]);
        }
    }
}

void processImage(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                  int height, int uvWidth, int multiplier, uint32_t* pixels, int pixelStride,
                  int numThreads) {
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        processRows(yData, uData, vData, width, height, uvWidth, multiplier, pixels, pixelStride,
                    start, end);
    });
}

}  // namespace edge_luminance

#ifndef VC_NO_JNI
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_EdgeLuminanceEffect_00024Companion_processImageNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jint width,
    jint height,
    jint multiplier,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint uvWidth,
    jobject bitmap,
    jint numThreads
) {
    vc::LockedBitmap locked(env, bitmap, width, height);
    if (!locked.ok()) {
        return JNI_FALSE;
    }
    jbyte* yPtr = env->GetByteArrayElements(yData, nullptr);
    jbyte* uPtr = env->GetByteArrayElements(uData, nullptr);
    jbyte* vPtr = env->GetByteArrayElements(vData, nullptr);
    if (!yPtr || !uPtr || !vPtr) {
        LOGE("Failed to get native arrays");
        if (yPtr) env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
        if (uPtr) env->ReleaseByteArrayElements(uData, uPtr, JNI_ABORT);
        if (vPtr) env->ReleaseByteArrayElements(vData, vPtr, JNI_ABORT);
        return JNI_FALSE;
    }
    edge_luminance::processImage(
            reinterpret_cast<const uint8_t*>(yPtr), reinterpret_cast<const uint8_t*>(uPtr),
            reinterpret_cast<const uint8_t*>(vPtr), width, height, uvWidth, multiplier,
            locked.pixels(), locked.stride(), std::max(1, static_cast<int>(numThreads)));
    env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(uData, uPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(vData, vPtr, JNI_ABORT);
    return JNI_TRUE;
}
#endif
