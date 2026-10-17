// Native implementation of ContourEffect: box blur the luminance, quantize it into levels, fill
// each level with a color and draw lines where neighboring levels differ. The arithmetic matches
// the Kotlin implementation exactly so both produce identical images.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry point and Android headers so the core
// functions can be built into standalone programs: the host unit test and the on-device
// benchmark in app/src/test/cpp.

#include <algorithm>
#include <cstdint>
#include <vector>

#include "box_blur.h"
#include "pixel_format.h"
#include "worker_pool.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "ContourEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace contour {

// Quantizes `count` blurred luminance bytes into `levels` bands.
inline void quantize(const uint8_t* src, uint8_t* levelsOut, int count, int levels) {
    const uint32_t ulevels = levels;
    for (int i = 0; i < count; i++) {
        levelsOut[i] = static_cast<uint8_t>((src[i] * ulevels) >> 8);
    }
}

// Fills image rows [startRow, endRow) with the level colors, drawing `lineColor` where a pixel's
// level differs from a neighbor's. Thickness 1 compares only the left and top neighbors;
// thickness 2 also compares right and bottom, giving two-pixel lines. `levelPlane` points at
// the levels of image row `levelOrigin` and must cover one row above and below the range
// wherever the image has such rows. Output rows are `pixelStride` int32s apart.
void renderRows(const uint8_t* levelPlane, int levelOrigin, int width, int height,
                const int32_t* fillColors, int32_t lineColor, int lineThickness, int32_t* pixels,
                int pixelStride, int startRow, int endRow) {
    for (int y = startRow; y < endRow; y++) {
        const uint8_t* row = levelPlane + (y - levelOrigin) * width;
        const uint8_t* above = (y > 0) ? row - width : nullptr;
        const uint8_t* below = (y < height - 1) ? row + width : nullptr;
        int32_t* out = pixels + static_cast<size_t>(y) * pixelStride;
        for (int x = 0; x < width; x++) {
            uint8_t level = row[x];
            bool isLine = (x > 0 && row[x - 1] != level) || (above && above[x] != level);
            if (!isLine && lineThickness > 1) {
                isLine = (x < width - 1 && row[x + 1] != level) || (below && below[x] != level);
            }
            out[x] = isLine ? lineColor : fillColors[level];
        }
    }
}

// Processes image rows [startRow, endRow) completely: horizontal blur, vertical blur with
// quantization, and rendering, using only this chunk's rows plus a small halo. The halo rows
// are blurred redundantly by neighboring chunks, which costs a few percent but means chunks
// have no dependencies on each other and the intermediate data stays in cache.
void processChunk(const uint8_t* yData, int width, int height, int levels, int smoothingRadius,
                  int lineThickness, const int32_t* fillColors, int32_t lineColor,
                  int32_t* pixels, int pixelStride, int startRow, int endRow) {
    // Rendering needs levels one row beyond the chunk; levels need blurred rows `radius` beyond
    // that. Rows outside the image are clamped exactly as the full-image blur would clamp them.
    const int levelStart = std::max(0, startRow - 1);
    const int levelEnd = std::min(height, endRow + 1);
    const int blurStart = std::max(0, levelStart - smoothingRadius);
    const int blurEnd = std::min(height, levelEnd + smoothingRadius);
    const int blurRowCount = blurEnd - blurStart;

    static thread_local std::vector<uint8_t> blurredBuf;
    static thread_local std::vector<uint8_t> levelsBuf;
    const size_t bufSize = static_cast<size_t>(blurRowCount) * width;
    if (blurredBuf.size() < bufSize) blurredBuf.resize(bufSize);
    if (levelsBuf.size() < bufSize) levelsBuf.resize(bufSize);

    if (smoothingRadius > 0) {
        vc::boxBlurRows(yData + static_cast<size_t>(blurStart) * width, blurredBuf.data(), width,
                        smoothingRadius, blurRowCount);
        // Levels are written at the same local row indices as the blurred rows they come from.
        vc::ColumnBoxBlur columns(blurredBuf.data(), width, width, blurRowCount, smoothingRadius,
                                  levelStart - blurStart);
        for (int y = levelStart; y < levelEnd; y++) {
            uint8_t* levelRow = levelsBuf.data() + static_cast<size_t>(y - blurStart) * width;
            columns.nextRow(levelRow);
            quantize(levelRow, levelRow, width, levels);
        }
    } else {
        // blurStart == levelStart here, so the levels buffer is indexed from levelStart.
        quantize(yData + static_cast<size_t>(levelStart) * width, levelsBuf.data(),
                 (levelEnd - levelStart) * width, levels);
    }
    renderRows(levelsBuf.data(), blurStart, width, height, fillColors, lineColor, lineThickness,
               pixels, pixelStride, startRow, endRow);
}

// Full effect: `pixels` receives `height` rows of `width` color values, rows `pixelStride`
// int32s apart; the colors are written as given. `fillColors` must have `levels` entries. Row
// chunks are claimed dynamically by `numThreads` threads (the worker pool plus the caller).
void processImage(const uint8_t* yData, int width, int height, int levels, int smoothingRadius,
                  int lineThickness, const int32_t* fillColors, int32_t lineColor,
                  int32_t* pixels, int pixelStride, int numThreads) {
    smoothingRadius = std::min(smoothingRadius, vc::kMaxBoxBlurRadius);
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        processChunk(yData, width, height, levels, smoothingRadius, lineThickness,
                     fillColors, lineColor, pixels, pixelStride, start, end);
    });
}

}  // namespace contour

#ifndef VC_NO_JNI
// Renders directly into `bitmap`, which must be RGBA_8888 (Bitmap.Config.ARGB_8888) and
// width x height. Returns false, without touching the bitmap, if it isn't; the caller then uses
// the int array path.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_ContourEffect_00024Companion_processImageNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jint width,
    jint height,
    jint levels,
    jint smoothingRadius,
    jint lineThickness,
    jintArray fillColors,
    jint lineColor,
    jobject bitmap,
    jint numThreads
) {
    if (env->GetArrayLength(fillColors) < levels) {
        LOGE("fillColors has fewer than %d entries", levels);
        return JNI_FALSE;
    }
    vc::LockedBitmap locked(env, bitmap, width, height);
    if (!locked.ok()) {
        return JNI_FALSE;
    }
    jbyte* yDataPtr = env->GetByteArrayElements(yData, nullptr);
    jint* fillColorsPtr = env->GetIntArrayElements(fillColors, nullptr);
    if (!yDataPtr || !fillColorsPtr) {
        LOGE("Failed to get native arrays");
        if (yDataPtr) env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
        if (fillColorsPtr) env->ReleaseIntArrayElements(fillColors, fillColorsPtr, JNI_ABORT);
        return JNI_FALSE;
    }
    std::vector<int32_t> bitmapFillColors(levels);
    for (int i = 0; i < levels; i++) {
        bitmapFillColors[i] = static_cast<int32_t>(vc::argbToRgba(fillColorsPtr[i]));
    }

    contour::processImage(reinterpret_cast<const uint8_t*>(yDataPtr), width, height, levels,
                          smoothingRadius, lineThickness, bitmapFillColors.data(),
                          static_cast<int32_t>(vc::argbToRgba(lineColor)),
                          reinterpret_cast<int32_t*>(locked.pixels()), locked.stride(),
                          std::max(1, static_cast<int>(numThreads)));

    env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(fillColors, fillColorsPtr, JNI_ABORT);
    return JNI_TRUE;
}
#endif
