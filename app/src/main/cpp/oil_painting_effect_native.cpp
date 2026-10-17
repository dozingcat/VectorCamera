// Native implementation of OilPaintingEffect: YUV is converted to quantized RGB, then each output
// pixel takes the most common color within an adaptive circular brush around it. Renders straight
// into the bitmap.
//
// The whole image is converted to RGB before the brush pass, so the brush sees real pixels across
// row-chunk boundaries (an earlier version converted only each thread's own rows and sampled
// zeros beyond them). Row chunks are then spread over the worker pool; rows are independent.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry point so the kernels can be unit tested on
// the host (app/src/test/cpp/oil_painting_test.cpp).

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#include "pixel_format.h"
#include "worker_pool.h"
#include "yuv.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "OilPaintingEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace oil {

/**
 * Pre-computed brush pattern for different radii.
 * Eliminates expensive distance calculations per pixel.
 * Supports incremental updates when sliding horizontally.
 */
struct BrushPattern {
    std::vector<std::pair<int16_t, int16_t>> offsets;
    // Incremental update patterns for horizontal sliding
    std::vector<std::pair<int16_t, int16_t>> leftEdgeToRemove;   // Pixels to remove when moving right
    std::vector<std::pair<int16_t, int16_t>> rightEdgeToAdd;  // Pixels to add when moving right
};

constexpr int kMaxBrushRadius = 20;

// Brush patterns for radii 0..kMaxBrushRadius, built once; read-only afterwards.
const std::vector<BrushPattern>& brushPatterns() {
    static const std::vector<BrushPattern> patterns = [] {
        std::vector<BrushPattern> result(kMaxBrushRadius + 1);
        for (int radius = 0; radius <= kMaxBrushRadius; radius++) {
            BrushPattern& pattern = result[radius];
            if (radius == 0) {
                // Special case: radius 0 means just the center pixel
                pattern.offsets.emplace_back(0, 0);
                continue;
            }
            const int radiusSquared = radius * radius;
            // Pre-compute all offsets within the circular brush
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (dx * dx + dy * dy <= radiusSquared) {
                        pattern.offsets.emplace_back(static_cast<int16_t>(dx), static_cast<int16_t>(dy));
                    }
                }
            }
            // Compute incremental update patterns for horizontal sliding
            for (const auto& p : pattern.offsets) {
                const int dx = p.first;
                const int dy = p.second;
                // If going left one pixel would put this point outside the radius,
                // the left-shifted point should be removed when sliding right.
                if ((dx - 1) * (dx - 1) + dy * dy > radiusSquared) {
                    pattern.leftEdgeToRemove.emplace_back(dx - 1, dy);
                }
                // If going right one pixel would put this point outside the radius,
                // it's on the right edge and should be added when sliding right.
                if ((dx + 1) * (dx + 1) + dy * dy > radiusSquared) {
                    pattern.rightEdgeToAdd.emplace_back(dx, dy);
                }
            }
        }
        return result;
    }();
    return patterns;
}

/**
 * Calculate brightness for contrast analysis.
 */
inline double calculateBrightness(uint32_t pixel) {
    const uint32_t r = (pixel >> 16) & 0xFF;
    const uint32_t g = (pixel >> 8) & 0xFF;
    const uint32_t b = pixel & 0xFF;
    return r * 0.299 + g * 0.587 + b * 0.114;
}

/**
 * Calculate adaptive brush size based on local contrast.
 */
int calculateAdaptiveBrushSize(const uint32_t* pixels, int centerX, int centerY, int width,
                               int height, int maxBrushSize, float contrastSensitivity) {
    const int sampleRadius = 3;
    double totalVariance = 0.0;
    int sampleCount = 0;

    const double centerBrightness = calculateBrightness(pixels[centerY * width + centerX]);

    // Sample surrounding pixels to measure local contrast
    for (int dy = -sampleRadius; dy <= sampleRadius; dy++) {
        for (int dx = -sampleRadius; dx <= sampleRadius; dx++) {
            const int sampleX = centerX + dx;
            const int sampleY = centerY + dy;
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                const double diff = calculateBrightness(pixels[sampleY * width + sampleX]) - centerBrightness;
                totalVariance += diff * diff;
                sampleCount++;
            }
        }
    }

    const double avgVariance = (sampleCount > 0) ? totalVariance / sampleCount : 0.0;
    const double normalizedVariance = std::min(1.0, avgVariance / (255.0 * 255.0));

    // High contrast = smaller brush, low contrast = larger brush
    const double adaptiveFactor = 1.0 - std::min(0.8, normalizedVariance * contrastSensitivity);
    const int adaptiveBrushSize = static_cast<int>(maxBrushSize * adaptiveFactor);

    return std::max(2, std::min(maxBrushSize, adaptiveBrushSize));
}

/**
 * Fast hash function for quantized colors: the top 5 bits of each channel of an ARGB pixel.
 */
inline uint32_t hashQuantizedColor(uint32_t color) {
    const uint32_t r = (color >> 19) & 0x1F;
    const uint32_t g = (color >> 11) & 0x1F;
    const uint32_t b = (color >> 3) & 0x1F;
    return (r << 10) | (g << 5) | b;    // 15-bit hash: 5+5+5 bits
}

enum ColorCountUpdateType {Full, Incremental};

// Per-thread state for the brush color histogram.
struct BrushState {
    std::vector<uint16_t> colorCounts = std::vector<uint16_t>(32768, 0);
    std::vector<uint32_t> usedColors;
    std::vector<uint32_t> colorSamples;

    void clear() {
        for (uint32_t colorHash : usedColors) {
            colorCounts[colorHash] = 0;
        }
        usedColors.clear();
        colorSamples.clear();
    }
};

/**
 * Update color counts for the brush at (centerX, centerY): either fully, or incrementally after
 * sliding one pixel to the right.
 */
void updateColorCounts(const uint32_t* pixels, int centerX, int centerY, int width, int height,
                       int radius, BrushState& state, ColorCountUpdateType updateType) {
    const auto& patterns = brushPatterns();
    radius = std::max(0, std::min(radius, static_cast<int>(patterns.size()) - 1));
    const BrushPattern& pattern = patterns[radius];

    auto add = [&](const std::pair<int16_t, int16_t>& offset) {
        const int sampleX = centerX + offset.first;
        const int sampleY = centerY + offset.second;
        if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
            const uint32_t samplePixel = pixels[sampleY * width + sampleX];
            const uint32_t colorHash = hashQuantizedColor(samplePixel);
            if (state.colorCounts[colorHash] == 0) {
                state.usedColors.push_back(colorHash);
                state.colorSamples.push_back(samplePixel);
            }
            state.colorCounts[colorHash]++;
        }
    };

    if (updateType == ColorCountUpdateType::Full) {
        for (const auto& offset : pattern.offsets) {
            add(offset);
        }
    } else {
        // Remove left edge pixels.
        for (const auto& offset : pattern.leftEdgeToRemove) {
            const int sampleX = centerX + offset.first;
            const int sampleY = centerY + offset.second;
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                const uint32_t colorHash = hashQuantizedColor(pixels[sampleY * width + sampleX]);
                if (state.colorCounts[colorHash] > 0) {
                    state.colorCounts[colorHash]--;
                }
            }
        }
        // Add right edge pixels.
        for (const auto& offset : pattern.rightEdgeToAdd) {
            add(offset);
        }
    }
}

/**
 * Find the most common color in the brush; the first color reaching the maximum count wins.
 */
uint32_t findDominantColorFromCounts(const BrushState& state, uint32_t fallbackColor) {
    uint32_t dominantColor = fallbackColor;
    int maxCount = 0;
    for (size_t i = 0; i < state.usedColors.size(); i++) {
        const uint32_t colorHash = state.usedColors[i];
        if (state.colorCounts[colorHash] > maxCount) {
            maxCount = state.colorCounts[colorHash];
            dominantColor = state.colorSamples[i];
        }
    }
    return dominantColor;
}

// Converts rows [startRow, endRow) of YUV to quantized ARGB. `quantizeLut` maps each channel
// value to its quantized value, replacing the two integer divisions per channel.
void convertRows(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                 int uvWidth, const uint8_t* quantizeLut, uint32_t* rgbPixels, int startRow,
                 int endRow) {
    for (int y = startRow; y < endRow; y++) {
        const uint8_t* yRow = yData + static_cast<size_t>(y) * width;
        const uint8_t* uRow = uData + static_cast<size_t>(y / 2) * uvWidth;
        const uint8_t* vRow = vData + static_cast<size_t>(y / 2) * uvWidth;
        uint32_t* out = rgbPixels + static_cast<size_t>(y) * width;
        for (int x = 0; x < width; x++) {
            const uint32_t rgb = YuvUtils::yuvToRgbFixed(yRow[x], uRow[x / 2], vRow[x / 2]);
            out[x] = vc::packArgb(quantizeLut[rgb >> 16], quantizeLut[(rgb >> 8) & 0xFF],
                                  quantizeLut[rgb & 0xFF]);
        }
    }
}

// Applies the brush to rows [startRow, endRow) of the converted image, writing bitmap pixels to
// `outputPixels` (rows `pixelStride` uint32s apart). Rows are independent: the histogram is
// reset at the start of each row.
void paintRows(const uint32_t* rgbPixels, int width, int height, int brushSize,
               float contrastSensitivity, uint32_t* outputPixels, int pixelStride, int startRow,
               int endRow) {
    static thread_local BrushState state;
    for (int y = startRow; y < endRow; y++) {
        state.clear();
        int previousBrushSize = -1;
        uint32_t* out = outputPixels + static_cast<size_t>(y) * pixelStride;
        for (int x = 0; x < width; x++) {
            const int pixelIndex = y * width + x;
            // Calculate adaptive brush size based on local contrast
            const int localBrushSize = calculateAdaptiveBrushSize(
                rgbPixels, x, y, width, height, brushSize, contrastSensitivity);

            if (localBrushSize == previousBrushSize && x > 0) {
                // Incremental update: slide brush horizontally
                updateColorCounts(rgbPixels, x, y, width, height, localBrushSize, state,
                                  ColorCountUpdateType::Incremental);
            } else {
                // Full recalculation: brush size changed or first pixel in row
                if (previousBrushSize != -1) {
                    state.clear();
                }
                updateColorCounts(rgbPixels, x, y, width, height, localBrushSize, state,
                                  ColorCountUpdateType::Full);
            }

            out[x] = vc::argbToRgba(findDominantColorFromCounts(state, rgbPixels[pixelIndex]));
            previousBrushSize = localBrushSize;
        }
    }
}

// Full effect into `outputPixels` (rows `pixelStride` uint32s apart) as bitmap pixels.
void processImage(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                  int height, int brushSize, int levels, float contrastSensitivity,
                  uint32_t* outputPixels, int pixelStride, int numThreads) {
    const int uvWidth = (width + 1) / 2;
    const int quantizationStep = std::max(1, 256 / std::max(1, levels));
    uint8_t quantizeLut[256];
    for (int i = 0; i < 256; i++) {
        quantizeLut[i] = static_cast<uint8_t>((i / quantizationStep) * quantizationStep);
    }

    // Converted image, kept between frames to avoid re-faulting a fresh allocation each time.
    static thread_local std::vector<uint32_t> rgbPixels;
    const size_t numPixels = static_cast<size_t>(width) * height;
    if (rgbPixels.size() < numPixels) rgbPixels.resize(numPixels);
    uint32_t* rgb = rgbPixels.data();

    brushPatterns();  // Build once before the parallel region.
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        convertRows(yData, uData, vData, width, uvWidth, quantizeLut, rgb, start, end);
    });
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        paintRows(rgb, width, height, brushSize, contrastSensitivity, outputPixels, pixelStride,
                  start, end);
    });
}

}  // namespace oil

#ifndef VC_NO_JNI
// Renders into `bitmap`, which must be RGBA_8888 (Bitmap.Config.ARGB_8888) and width x height.
// Returns false, without touching the bitmap, if it can't be used; the caller then falls back.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_OilPaintingEffect_00024Companion_processImageNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint width,
    jint height,
    jint brushSize,
    jint levels,
    jfloat contrastSensitivity,
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
    oil::processImage(
            reinterpret_cast<const uint8_t*>(yPtr), reinterpret_cast<const uint8_t*>(uPtr),
            reinterpret_cast<const uint8_t*>(vPtr), width, height, brushSize, levels,
            contrastSensitivity, locked.pixels(), locked.stride(),
            std::max(1, static_cast<int>(numThreads)));
    env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(uData, uPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(vData, vPtr, JNI_ABORT);
    return JNI_TRUE;
}
#endif
