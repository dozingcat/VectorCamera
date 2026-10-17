// Native implementation of StainedGlassEffect: the image is divided into Voronoi-like segments
// around jittered grid seed points, each segment is filled with its average color (optionally
// perturbed), and segment boundaries are drawn as edges. Renders straight into the bitmap.
//
// The segment map depends only on the image size and segment size, so it is cached between
// frames. Color accumulation and rendering are spread over the worker pool in row chunks.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry point so the kernels can be unit tested on
// the host (app/src/test/cpp/stained_glass_test.cpp).

#include <algorithm>
#include <climits>
#include <cstdint>
#include <random>
#include <vector>

#include "pixel_format.h"
#include "worker_pool.h"
#include "yuv.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "StainedGlassNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace stained_glass {

/**
 * Seed point structure for segment generation
 */
struct SeedPoint {
    int x;
    int y;
    int segmentId;

    SeedPoint() : x(0), y(0), segmentId(0) {}
    SeedPoint(int x, int y, int segmentId) : x(x), y(y), segmentId(segmentId) {}
};

/**
 * Segment map for one image size and segment size, kept between frames.
 */
struct SegmentMap {
    int width = 0;
    int height = 0;
    int segmentSize = 0;
    std::vector<std::vector<SeedPoint>> seedGrid;
    std::vector<int> segmentMap;
    size_t totalSegments = 0;

    [[nodiscard]] bool matches(int w, int h, int segSize) const {
        return width == w && height == h && segmentSize == segSize && !segmentMap.empty();
    }
};

/**
 * Generate seed points in a grid pattern with random variation
 */
std::vector<std::vector<SeedPoint>> generateSeedPoints(int width, int height, int avgSegmentSize,
                                                       std::mt19937& rng) {
    std::vector<std::vector<SeedPoint>> seedGrid;
    const int gridSpacing = avgSegmentSize;
    const int variation = avgSegmentSize / 3;
    std::uniform_int_distribution<int> variationDist(-variation, variation);
    int segmentId = 0;
    for (int y = gridSpacing / 2; y < height; y += gridSpacing) {
        std::vector<SeedPoint> currentRow;
        for (int x = gridSpacing / 2; x < width; x += gridSpacing) {
            // Add random variation to seed point positions
            int randomX = std::clamp(x + variationDist(rng), 0, width - 1);
            int randomY = std::clamp(y + variationDist(rng), 0, height - 1);
            currentRow.emplace_back(randomX, randomY, segmentId++);
        }
        if (!currentRow.empty()) {
            seedGrid.push_back(std::move(currentRow));
        }
    }
    return seedGrid;
}

/**
 * Assign rows [startY, endY) of the segment map to the nearest seed point, searching only the
 * 3x3 neighborhood of grid cells (mathematically sufficient).
 */
void createSegmentMapRows(int width, const std::vector<std::vector<SeedPoint>>& seedGrid,
                          int gridSpacing, std::vector<int>& segmentMap, int startY, int endY) {
    for (int y = startY; y < endY; y++) {
        const int cellY = y / gridSpacing;
        const int minCellY = std::max(0, cellY - 1);
        const int maxCellY = std::min(static_cast<int>(seedGrid.size()) - 1, cellY + 1);
        for (int x = 0; x < width; x++) {
            const int cellX = x / gridSpacing;
            const int minCellX = std::max(0, cellX - 1);
            int minDistanceSquared = INT_MAX;
            int nearestSegment = 0;
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                const auto& row = seedGrid[cy];
                const int maxCellX = std::min(static_cast<int>(row.size()) - 1, cellX + 1);
                for (int cx = minCellX; cx <= maxCellX; cx++) {
                    const SeedPoint& point = row[cx];
                    const int dx = x - point.x;
                    const int dy = y - point.y;
                    const int distanceSquared = dx * dx + dy * dy;
                    if (distanceSquared < minDistanceSquared) {
                        minDistanceSquared = distanceSquared;
                        nearestSegment = point.segmentId;
                    }
                }
            }
            segmentMap[y * width + x] = nearestSegment;
        }
    }
}

// Returns the cached segment map for this size, rebuilding it if the size changed. The cache is
// only touched from the calling thread; the parallel rows below only read it.
const SegmentMap& cachedSegmentMap(int width, int height, int segmentSize, int numThreads) {
    static SegmentMap cache;
    if (cache.matches(width, height, segmentSize)) {
        return cache;
    }
    // Deterministic randomization so the segments don't jitter between frames.
    std::mt19937 rng(width * height + segmentSize);
    cache.width = width;
    cache.height = height;
    cache.segmentSize = segmentSize;
    cache.seedGrid = generateSeedPoints(width, height, segmentSize, rng);
    cache.segmentMap.assign(static_cast<size_t>(width) * height, 0);
    cache.totalSegments = 0;
    for (const auto& row : cache.seedGrid) {
        cache.totalSegments += row.size();
    }
    if (!cache.seedGrid.empty()) {
        vc::parallelRows(numThreads, height, [&](int start, int end) {
            createSegmentMapRows(width, cache.seedGrid, segmentSize, cache.segmentMap, start, end);
        });
    }
    return cache;
}

// Per-segment RGB totals and pixel counts.
struct ColorTotals {
    std::vector<uint64_t> red, green, blue;
    std::vector<uint32_t> count;

    void resize(size_t n) {
        red.assign(n, 0);
        green.assign(n, 0);
        blue.assign(n, 0);
        count.assign(n, 0);
    }
};

/**
 * Accumulate the RGB totals of rows [startY, endY) into `totals`, which is indexed by segment.
 */
void accumulateSegmentColors(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
                             int width, const std::vector<int>& segmentMap, size_t totalSegments,
                             uint64_t* red, uint64_t* green, uint64_t* blue, uint32_t* count,
                             int startY, int endY) {
    const int uvWidth = width / 2;
    for (int y = startY; y < endY; y++) {
        const uint8_t* yRow = yData + static_cast<size_t>(y) * width;
        const uint8_t* uRow = uData + static_cast<size_t>(y / 2) * uvWidth;
        const uint8_t* vRow = vData + static_cast<size_t>(y / 2) * uvWidth;
        const int* segRow = segmentMap.data() + static_cast<size_t>(y) * width;
        for (int x = 0; x < width; x++) {
            const int segmentId = segRow[x];
            if (static_cast<size_t>(segmentId) < totalSegments) {
                const uint32_t rgb = YuvUtils::yuvToRgbFixed(yRow[x], uRow[x / 2], vRow[x / 2], false);
                red[segmentId] += (rgb >> 16) & 0xFF;
                green[segmentId] += (rgb >> 8) & 0xFF;
                blue[segmentId] += rgb & 0xFF;
                count[segmentId]++;
            }
        }
    }
}

/**
 * Render rows [startY, endY): each pixel gets its segment's color, or the edge color if the
 * original push-style edge drawing would have painted it. That drew `edgeThickness` pixels to
 * the right of each pixel whose right neighbor is in another segment, and below each pixel
 * whose bottom neighbor is, scanning x in [0, width-1) and y in [0, height-1). Here each pixel
 * looks back instead, so rows can be rendered independently, with identical results.
 */
void renderRows(int width, int height, const std::vector<int>& segmentMap,
                const std::vector<uint32_t>& segmentColors, uint32_t* outputPixels, int pixelStride,
                int edgeThickness, uint32_t edgeColor, int startY, int endY) {
    const int* seg = segmentMap.data();
    for (int y = startY; y < endY; y++) {
        uint32_t* out = outputPixels + static_cast<size_t>(y) * pixelStride;
        const int* segRow = seg + static_cast<size_t>(y) * width;
        for (int x = 0; x < width; x++) {
            bool isEdge = false;
            if (edgeThickness > 0) {
                // Horizontal: a boundary at (x - t, y) between it and (x - t + 1, y).
                if (y < height - 1) {
                    const int tMax = std::min(edgeThickness, x + 1);
                    for (int t = 0; t < tMax; t++) {
                        const int sx = x - t;
                        if (sx < width - 1 && segRow[sx] != segRow[sx + 1]) {
                            isEdge = true;
                            break;
                        }
                    }
                }
                // Vertical: a boundary at (x, y - t) between it and (x, y - t + 1).
                if (!isEdge && x < width - 1) {
                    const int tMax = std::min(edgeThickness, y + 1);
                    for (int t = 0; t < tMax; t++) {
                        const int sy = y - t;
                        if (sy < height - 1 &&
                            seg[static_cast<size_t>(sy) * width + x] != seg[static_cast<size_t>(sy + 1) * width + x]) {
                            isEdge = true;
                            break;
                        }
                    }
                }
            }
            if (isEdge) {
                out[x] = edgeColor;
            } else {
                const int segmentId = segRow[x];
                out[x] = static_cast<size_t>(segmentId) < segmentColors.size()
                         ? segmentColors[segmentId] : vc::packRgba(0, 0, 0);
            }
        }
    }
}

// Full effect into `outputPixels` (rows `pixelStride` uint32s apart) as bitmap pixels.
// `edgeColor` is an ARGB color int. Returns false if no segments could be generated.
bool processImage(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
                  int height, int segmentSize, int edgeThickness, uint32_t edgeColor,
                  float colorVariation, uint32_t* outputPixels, int pixelStride, int numThreads) {
    const SegmentMap& segments = cachedSegmentMap(width, height, segmentSize, numThreads);
    if (segments.seedGrid.empty()) {
        return false;
    }
    const size_t totalSegments = segments.totalSegments;

    // Each row chunk accumulates into its own slot so the sums are exact regardless of which
    // thread runs which chunk; the slots are reduced afterwards.
    const int chunkRows = vc::kDefaultChunkRows;
    const int numChunks = (height + chunkRows - 1) / chunkRows;
    static thread_local ColorTotals chunkTotals;
    chunkTotals.resize(static_cast<size_t>(numChunks) * totalSegments);
    // Plain pointers: the lambda runs on worker threads, where the thread_local above would
    // name a different (empty) instance.
    uint64_t* const red = chunkTotals.red.data();
    uint64_t* const green = chunkTotals.green.data();
    uint64_t* const blue = chunkTotals.blue.data();
    uint32_t* const count = chunkTotals.count.data();
    vc::parallelRows(numThreads, height, chunkRows, [&](int start, int end) {
        const size_t slot = static_cast<size_t>(start / chunkRows) * totalSegments;
        accumulateSegmentColors(yData, uData, vData, width, segments.segmentMap, totalSegments,
                                red + slot, green + slot, blue + slot, count + slot, start, end);
    });
    std::vector<uint64_t> redTotals(totalSegments, 0), greenTotals(totalSegments, 0), blueTotals(totalSegments, 0);
    std::vector<uint32_t> pixelCounts(totalSegments, 0);
    for (int c = 0; c < numChunks; c++) {
        const size_t slot = static_cast<size_t>(c) * totalSegments;
        for (size_t i = 0; i < totalSegments; i++) {
            redTotals[i] += chunkTotals.red[slot + i];
            greenTotals[i] += chunkTotals.green[slot + i];
            blueTotals[i] += chunkTotals.blue[slot + i];
            pixelCounts[i] += chunkTotals.count[slot + i];
        }
    }

    // Convert totals to final colors with variation, as bitmap pixels.
    std::vector<uint32_t> segmentColors(totalSegments);
    std::mt19937 colorRng(width * height + segmentSize + 42); // Different seed for color variation
    std::uniform_int_distribution<int> variationDist(-static_cast<int>(colorVariation * 128),
                                                    static_cast<int>(colorVariation * 128));
    for (size_t i = 0; i < totalSegments; i++) {
        uint32_t r = 0, g = 0, b = 0;  // Black fallback
        if (pixelCounts[i] > 0) {
            r = static_cast<uint32_t>(redTotals[i] / pixelCounts[i]);
            g = static_cast<uint32_t>(greenTotals[i] / pixelCounts[i]);
            b = static_cast<uint32_t>(blueTotals[i] / pixelCounts[i]);
        }
        if (colorVariation > 0.0f && pixelCounts[i] > 0) {
            r = std::clamp(static_cast<int>(r) + variationDist(colorRng), 0, 255);
            g = std::clamp(static_cast<int>(g) + variationDist(colorRng), 0, 255);
            b = std::clamp(static_cast<int>(b) + variationDist(colorRng), 0, 255);
        }
        segmentColors[i] = vc::packRgba(r, g, b);
    }

    const uint32_t bitmapEdgeColor = vc::argbToRgba(edgeColor);
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        renderRows(width, height, segments.segmentMap, segmentColors, outputPixels, pixelStride,
                   edgeThickness, bitmapEdgeColor, start, end);
    });
    return true;
}

}  // namespace stained_glass

#ifndef VC_NO_JNI
// Renders into `bitmap`, which must be RGBA_8888 (Bitmap.Config.ARGB_8888) and width x height.
// Returns false, without a usable result, if it can't; the caller then falls back.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_StainedGlassEffect_00024Companion_processImageNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint width,
    jint height,
    jint segmentSize,
    jint edgeThickness,
    jint edgeColor,
    jfloat colorVariation,
    jobject bitmap,
    jint numThreads
) {
    if (segmentSize <= 0) {
        LOGE("Invalid segment size %d", segmentSize);
        return JNI_FALSE;
    }
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
    bool ok = false;
    try {
        ok = stained_glass::processImage(
                reinterpret_cast<const uint8_t*>(yPtr), reinterpret_cast<const uint8_t*>(uPtr),
                reinterpret_cast<const uint8_t*>(vPtr), width, height, segmentSize, edgeThickness,
                static_cast<uint32_t>(edgeColor), colorVariation, locked.pixels(), locked.stride(),
                std::max(1, static_cast<int>(numThreads)));
    } catch (const std::exception& e) {
        LOGE("Exception in stained glass processing: %s", e.what());
    }
    env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(uData, uPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(vData, vPtr, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
#endif
