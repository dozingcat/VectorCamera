// Native implementation of CartoonEffect: each pixel's RGB is quantized to 4 levels per channel
// and the result is box blurred. Renders straight into the bitmap.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry point so the kernels can be unit tested on
// the host (app/src/test/cpp/cartoon_test.cpp).

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "box_blur.h"
#include "pixel_format.h"
#include "worker_pool.h"
#include "yuv.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "CartoonEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace cartoon {

// Color quantization LUT that reduces each color component to 2 bits (4 levels). Possible
// values are 0, 85, 170 and 255.
struct ColorLut {
    ColorLut() {
        for (int index = 0; index < 256; index++) {
            table[index] = static_cast<uint8_t>(static_cast<int>(std::round(index / 85.0)) * 85);
        }
    }
    uint8_t table[256];
};

// Quantizes `numRows` image rows starting at `firstRow` into planar R, G, B bytes.
void quantizeRows(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                  int uvWidth, const ColorLut& lut, int firstRow, int numRows,
                  uint8_t* rPlane, uint8_t* gPlane, uint8_t* bPlane) {
    for (int i = 0; i < numRows; i++) {
        const int y = firstRow + i;
        const uint8_t* yRow = yData + static_cast<size_t>(y) * width;
        const uint8_t* uRow = uData + static_cast<size_t>(y / 2) * uvWidth;
        const uint8_t* vRow = vData + static_cast<size_t>(y / 2) * uvWidth;
        uint8_t* rOut = rPlane + static_cast<size_t>(i) * width;
        uint8_t* gOut = gPlane + static_cast<size_t>(i) * width;
        uint8_t* bOut = bPlane + static_cast<size_t>(i) * width;
        for (int x = 0; x < width; x++) {
            const uint32_t rgb = YuvUtils::yuvToRgb(yRow[x], uRow[x / 2], vRow[x / 2], false);
            rOut[x] = lut.table[(rgb >> 16) & 0xFF];
            gOut[x] = lut.table[(rgb >> 8) & 0xFF];
            bOut[x] = lut.table[rgb & 0xFF];
        }
    }
}

// Processes image rows [startRow, endRow) completely: quantize the rows plus `radius` halo rows
// above and below into planar scratch, blur horizontally, then blur vertically while packing
// into the output. Halo rows are quantized and blurred redundantly by neighboring chunks, which
// costs a little but keeps chunks independent and the intermediates in cache.
void processChunk(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                  int height, int uvWidth, int radius, const ColorLut& lut, uint32_t* pixels,
                  int pixelStride, int startRow, int endRow) {
    const int blurStart = std::max(0, startRow - radius);
    const int blurEnd = std::min(height, endRow + radius);
    const int blurRowCount = blurEnd - blurStart;
    const size_t planeSize = static_cast<size_t>(blurRowCount) * width;

    // Three quantized planes, three horizontally blurred planes, three blurred output rows.
    static thread_local std::vector<uint8_t> quantized;
    static thread_local std::vector<uint8_t> blurred;
    static thread_local std::vector<uint8_t> outRows;
    if (quantized.size() < 3 * planeSize) quantized.resize(3 * planeSize);
    if (blurred.size() < 3 * planeSize) blurred.resize(3 * planeSize);
    if (outRows.size() < 3 * static_cast<size_t>(width)) outRows.resize(3 * width);
    uint8_t* q[3] = {quantized.data(), quantized.data() + planeSize, quantized.data() + 2 * planeSize};
    uint8_t* h[3] = {blurred.data(), blurred.data() + planeSize, blurred.data() + 2 * planeSize};
    uint8_t* o[3] = {outRows.data(), outRows.data() + width, outRows.data() + 2 * width};

    quantizeRows(yData, uData, vData, width, uvWidth, lut, blurStart, blurRowCount, q[0], q[1], q[2]);

    if (radius == 0) {
        // No blur: the quantized colors are the output.
        for (int y = startRow; y < endRow; y++) {
            const size_t local = static_cast<size_t>(y - blurStart) * width;
            uint32_t* out = pixels + static_cast<size_t>(y) * pixelStride;
            for (int x = 0; x < width; x++) {
                out[x] = vc::packRgba(q[0][local + x], q[1][local + x], q[2][local + x]);
            }
        }
        return;
    }

    for (int plane = 0; plane < 3; plane++) {
        vc::boxBlurRows(q[plane], h[plane], width, radius, blurRowCount);
    }
    vc::ColumnBoxBlur columns[3] = {
        vc::ColumnBoxBlur(h[0], width, width, blurRowCount, radius, startRow - blurStart),
        vc::ColumnBoxBlur(h[1], width, width, blurRowCount, radius, startRow - blurStart),
        vc::ColumnBoxBlur(h[2], width, width, blurRowCount, radius, startRow - blurStart)};
    for (int y = startRow; y < endRow; y++) {
        for (int plane = 0; plane < 3; plane++) {
            columns[plane].nextRow(o[plane]);
        }
        uint32_t* out = pixels + static_cast<size_t>(y) * pixelStride;
        for (int x = 0; x < width; x++) {
            out[x] = vc::packRgba(o[0][x], o[1][x], o[2][x]);
        }
    }
}

// Full effect into `pixels` (rows `pixelStride` uint32s apart) as bitmap pixels. Row chunks are
// spread over `numThreads` threads.
void processImage(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                  int height, int blurRadius, uint32_t* pixels, int pixelStride, int numThreads) {
    static const ColorLut lut;
    const int uvWidth = (width + 1) / 2;
    const int radius = std::max(0, std::min(blurRadius, vc::kMaxBoxBlurRadius));
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        processChunk(yData, uData, vData, width, height, uvWidth, radius, lut, pixels,
                     pixelStride, start, end);
    });
}

}  // namespace cartoon

#ifndef VC_NO_JNI
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_CartoonEffect_00024Companion_processImageNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint width,
    jint height,
    jint blurRadius,
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
    cartoon::processImage(
            reinterpret_cast<const uint8_t*>(yPtr), reinterpret_cast<const uint8_t*>(uPtr),
            reinterpret_cast<const uint8_t*>(vPtr), width, height, blurRadius, locked.pixels(),
            locked.stride(), std::max(1, static_cast<int>(numThreads)));
    env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(uData, uPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(vData, vPtr, JNI_ABORT);
    return JNI_TRUE;
}
#endif
