// Native implementation of PermuteColorEffect: each output channel is chosen from the pixel's
// red, green, blue or brightness (or their inverses, or a constant). Renders straight into the
// bitmap.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry point so the kernels can be unit tested on
// the host (app/src/test/cpp/effects_test.cpp).

#include <algorithm>
#include <cstdint>

#include "pixel_format.h"
#include "worker_pool.h"
#include "yuv.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "PermuteColorEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace permute {

// A ColorComponentSource code (the rsCode values of the Kotlin enum) decoded once per frame so
// the per-pixel work is an index and a conditional inversion instead of a switch.
struct ComponentSelector {
    explicit ComponentSelector(int source) {
        switch (source) {
            case 1: case 2: case 3: case 4: index = source - 1; break;              // R, G, B, Y
            case 5: case 6: case 7: case 8: index = source - 5; invert = true; break;  // inverses
            case -1: constant = 255; break;                                         // MAX
            default: constant = 0; break;                                           // MIN
        }
    }

    // `components` holds r, g, b, y.
    uint32_t select(const int components[4]) const {
        if (index < 0) return constant;
        const int value = components[index];
        return invert ? 255 - value : value;
    }

    int index = -1;
    bool invert = false;
    int constant = 0;
};

void processRows(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                 int uvWidth, const ComponentSelector& red, const ComponentSelector& green,
                 const ComponentSelector& blue, bool flipUV, uint32_t* pixels, int pixelStride,
                 int startRow, int endRow) {
    for (int y = startRow; y < endRow; y++) {
        const uint8_t* yRow = yData + static_cast<size_t>(y) * width;
        const uint8_t* uRow = uData + static_cast<size_t>(y / 2) * uvWidth;
        const uint8_t* vRow = vData + static_cast<size_t>(y / 2) * uvWidth;
        uint32_t* out = pixels + static_cast<size_t>(y) * pixelStride;
        for (int x = 0; x < width; x++) {
            const int yy = yRow[x];
            int u = uRow[x / 2];
            int v = vRow[x / 2];
            if (flipUV) {
                // Rotates the color 180 degrees in UV space.
                u = -u & 0xFF;
                v = -v & 0xFF;
            }
            const uint32_t rgb = YuvUtils::yuvToRgb(yy, u, v, false);
            const int components[4] = {
                static_cast<int>(rgb >> 16), static_cast<int>((rgb >> 8) & 0xFF),
                static_cast<int>(rgb & 0xFF), yy};
            out[x] = vc::packRgba(red.select(components), green.select(components),
                                  blue.select(components));
        }
    }
}

void processImage(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                  int height, int redSource, int greenSource, int blueSource, bool flipUV,
                  uint32_t* pixels, int pixelStride, int numThreads) {
    const int uvWidth = (width + 1) / 2;
    const ComponentSelector red(redSource), green(greenSource), blue(blueSource);
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        processRows(yData, uData, vData, width, uvWidth, red, green, blue, flipUV, pixels,
                    pixelStride, start, end);
    });
}

}  // namespace permute

#ifndef VC_NO_JNI
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_PermuteColorEffect_00024Companion_processImageNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint width,
    jint height,
    jint redSource,
    jint greenSource,
    jint blueSource,
    jboolean flipUV,
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
    permute::processImage(
            reinterpret_cast<const uint8_t*>(yPtr), reinterpret_cast<const uint8_t*>(uPtr),
            reinterpret_cast<const uint8_t*>(vPtr), width, height, redSource, greenSource,
            blueSource, flipUV == JNI_TRUE, locked.pixels(), locked.stride(),
            std::max(1, static_cast<int>(numThreads)));
    env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(uData, uPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(vData, vPtr, JNI_ABORT);
    return JNI_TRUE;
}
#endif
