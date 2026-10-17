// Native implementation of ContourEffect: box blur the luminance, quantize it into levels, fill
// each level with a color and draw lines where neighboring levels differ. The arithmetic matches
// the Kotlin implementation exactly so both produce identical images.
//
// Compiling with -DCONTOUR_NO_JNI leaves out the JNI entry point and Android headers so the core
// functions can be built into standalone programs: the host unit test and the on-device
// benchmark in app/src/test/cpp.

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <vector>

#include "worker_pool.h"

#ifndef CONTOUR_NO_JNI
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#define LOG_TAG "ContourEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace contour {

inline int clampInt(int value, int minVal, int maxVal) {
    return std::max(minVal, std::min(value, maxVal));
}

// Largest supported blur radius; see ExactDivider.
constexpr int kMaxSmoothingRadius = 127;

// Exact unsigned division by a runtime constant using a multiply and shift. Integer division is
// slow on mobile cores (around 12 cycles, not pipelined on little cores) and blocks
// vectorization, which matters when it runs twice per pixel. With a 24-bit reciprocal the result
// is exact for all values up to 255 * divisor as long as divisor <= 256, and the product stays
// within 32 bits so the compiler can vectorize it.
struct ExactDivider {
    explicit ExactDivider(uint32_t divisor)
        : reciprocal(((1u << 24) + divisor - 1) / divisor) {}

    uint32_t operator()(uint32_t value) const {
        return (value * reciprocal) >> 24;
    }

    const uint32_t reciprocal;
};

// Rows per work chunk. Chunks are handed out dynamically, so fast cores take more of them and
// slow cores take fewer; every thread finishes within about one chunk of the others regardless
// of the device's core mix.
constexpr int kChunkRows = 32;

// Calls fn(startRow, endRow) for chunks of rows claimed from `nextChunk` until none are left.
// Each pass has its own counter so no reset is needed between passes.
template <typename Fn>
void forEachChunk(std::atomic<int>& nextChunk, int height, Fn fn) {
    const int numChunks = (height + kChunkRows - 1) / kChunkRows;
    for (int c = nextChunk.fetch_add(1, std::memory_order_relaxed); c < numChunks;
         c = nextChunk.fetch_add(1, std::memory_order_relaxed)) {
        fn(c * kChunkRows, std::min(height, (c + 1) * kChunkRows));
    }
}

// Runs fn() on `numThreads` threads: the worker pool plus the calling thread.
template <typename Fn>
void runOnThreads(int numThreads, Fn fn) {
    if (numThreads <= 1) {
        fn();
        return;
    }
    WorkerPool::instance().run(numThreads, [&](int) { fn(); });
}

// Horizontal pass of the separable box blur for `numRows` rows starting at `src`, with clamped
// edges. Rows are independent, so callers pass pointers to whichever rows they need.
void blurRows(const uint8_t* src, uint8_t* dst, int width, int radius, int numRows) {
    const ExactDivider divide(2 * radius + 1);
    for (int y = 0; y < numRows; y++) {
        const uint8_t* row = src + y * width;
        uint8_t* out = dst + y * width;
        // Running sum; the subtraction never takes it below zero so unsigned wraparound is safe.
        uint32_t total = 0;
        for (int k = -radius; k <= radius; k++) {
            total += row[clampInt(k, 0, width - 1)];
        }
        for (int x = 0; x < width; x++) {
            out[x] = static_cast<uint8_t>(divide(total));
            total += row[clampInt(x + radius + 1, 0, width - 1)] -
                     row[clampInt(x - radius, 0, width - 1)];
        }
    }
}

// Vertical pass of the box blur for rows [startRow, endRow) of a buffer holding `height` rows,
// quantizing the result into `levels` bands. Running column sums are seeded from the rows
// around startRow, so row ranges can be processed independently.
void blurColumnsAndQuantize(const uint8_t* src, uint8_t* levelsOut, int width, int height,
                            int radius, int levels, int startRow, int endRow) {
    const ExactDivider divide(2 * radius + 1);
    const uint32_t ulevels = levels;
    std::vector<uint32_t> columnTotals(width, 0);
    for (int k = -radius; k <= radius; k++) {
        const uint8_t* row = src + clampInt(startRow + k, 0, height - 1) * width;
        for (int x = 0; x < width; x++) {
            columnTotals[x] += row[x];
        }
    }
    for (int y = startRow; y < endRow; y++) {
        uint8_t* out = levelsOut + y * width;
        const uint8_t* addRow = src + clampInt(y + radius + 1, 0, height - 1) * width;
        const uint8_t* subRow = src + clampInt(y - radius, 0, height - 1) * width;
        for (int x = 0; x < width; x++) {
            out[x] = static_cast<uint8_t>((divide(columnTotals[x]) * ulevels) >> 8);
            columnTotals[x] += addRow[x] - subRow[x];
        }
    }
}

// Quantizes `numRows` rows of unblurred luminance directly, for smoothing radius 0.
void quantizeRows(const uint8_t* src, uint8_t* levelsOut, int width, int levels, int numRows) {
    const uint32_t ulevels = levels;
    for (int i = 0; i < numRows * width; i++) {
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
        blurRows(yData + static_cast<size_t>(blurStart) * width, blurredBuf.data(), width,
                 smoothingRadius, blurRowCount);
        // Levels are written at the same local row indices as the blurred rows they come from.
        blurColumnsAndQuantize(blurredBuf.data(), levelsBuf.data(), width, blurRowCount,
                               smoothingRadius, levels, levelStart - blurStart,
                               levelEnd - blurStart);
    } else {
        // blurStart == levelStart here, so the levels buffer is indexed from levelStart.
        quantizeRows(yData + static_cast<size_t>(levelStart) * width, levelsBuf.data(), width,
                     levels, levelEnd - levelStart);
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
    const int numChunks = (height + kChunkRows - 1) / kChunkRows;
    numThreads = std::max(1, std::min(numThreads, numChunks));
    smoothingRadius = std::min(smoothingRadius, kMaxSmoothingRadius);
    std::atomic<int> nextChunk{0};
    runOnThreads(numThreads, [&] {
        forEachChunk(nextChunk, height, [&](int start, int end) {
            processChunk(yData, width, height, levels, smoothingRadius, lineThickness,
                         fillColors, lineColor, pixels, pixelStride, start, end);
        });
    });
}

}  // namespace contour

#ifndef CONTOUR_NO_JNI
// Converts an ARGB color int (as used by android.graphics.Color) to the in-memory layout of an
// RGBA_8888 bitmap pixel on a little-endian device: bytes R, G, B, A. Alpha is forced opaque, so
// premultiplication is the identity.
static int32_t argbToBitmapPixel(int32_t argb) {
    const uint32_t c = static_cast<uint32_t>(argb);
    return static_cast<int32_t>(0xFF000000u | ((c & 0xFFu) << 16) | (c & 0xFF00u) | ((c >> 16) & 0xFFu));
}

// Renders directly into `bitmap`, which must be RGBA_8888 (Bitmap.Config.ARGB_8888) and
// width x height. Returns false, without touching the bitmap, if it isn't; the caller then uses
// the int array path. This skips the Java pixel array and Bitmap.setPixels copy, which
// otherwise cost more than the effect itself at camera resolution.
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
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        static_cast<int>(info.width) != width || static_cast<int>(info.height) != height ||
        info.stride % 4 != 0) {
        LOGE("Bitmap is not a %dx%d RGBA_8888 bitmap", width, height);
        return JNI_FALSE;
    }
    if (env->GetArrayLength(fillColors) < levels) {
        LOGE("fillColors has fewer than %d entries", levels);
        return JNI_FALSE;
    }
    void* bitmapPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock bitmap pixels");
        return JNI_FALSE;
    }
    jbyte* yDataPtr = env->GetByteArrayElements(yData, nullptr);
    jint* fillColorsPtr = env->GetIntArrayElements(fillColors, nullptr);
    if (!yDataPtr || !fillColorsPtr) {
        LOGE("Failed to get native arrays");
        if (yDataPtr) env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
        if (fillColorsPtr) env->ReleaseIntArrayElements(fillColors, fillColorsPtr, JNI_ABORT);
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_FALSE;
    }
    std::vector<int32_t> bitmapFillColors(levels);
    for (int i = 0; i < levels; i++) {
        bitmapFillColors[i] = argbToBitmapPixel(fillColorsPtr[i]);
    }

    contour::processImage(reinterpret_cast<const uint8_t*>(yDataPtr), width, height, levels,
                          smoothingRadius, lineThickness, bitmapFillColors.data(),
                          argbToBitmapPixel(lineColor), static_cast<int32_t*>(bitmapPixels),
                          info.stride / 4, std::max(1, static_cast<int>(numThreads)));

    env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(fillColors, fillColorsPtr, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
#endif
