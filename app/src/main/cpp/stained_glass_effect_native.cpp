#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cmath>
#include <thread>
#include <vector>
#include <random>
#include <atomic>
#include "yuv.h"

#define LOG_TAG "StainedGlassNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace YuvUtils;

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
 * Cache for segment map to avoid recomputing every frame
 */
struct SegmentMapCache {
    int width = 0;
    int height = 0;
    int segmentSize = 0;
    std::vector<std::vector<SeedPoint>> seedGrid;
    std::vector<int> segmentMap;
    
    [[nodiscard]] bool isValid(int w, int h, int segSize) const {
        return width == w && height == h && segmentSize == segSize && !segmentMap.empty();
    }
};

// Global cache (thread-safe since we only read/write on main thread)
static SegmentMapCache g_segmentCache;

// Forward declarations
std::vector<std::vector<SeedPoint>> generateSeedPoints(int width, int height, int avgSegmentSize, std::mt19937& rng);
void createSegmentMap(int width, int height, const std::vector<std::vector<SeedPoint>>& seedGrid, int gridSpacing, std::vector<int>& segmentMap, int startY, int endY);

/**
 * Get cached segment map or create new one if cache is invalid
 */
std::pair<std::vector<int>, std::vector<std::vector<SeedPoint>>> getCachedSegmentMap(
    int width, 
    int height, 
    int segmentSize
) {
    // Check if cache is valid
    if (g_segmentCache.isValid(width, height, segmentSize)) {
        return std::make_pair(g_segmentCache.segmentMap, g_segmentCache.seedGrid);
    }
    
    // Cache is invalid, create new segment map
    LOGI("Creating new segment map for %dx%d, segmentSize=%d", width, height, segmentSize);
    
    // Generate seed points with deterministic randomization
    std::mt19937 rng(width * height + segmentSize);
    auto seedGrid = generateSeedPoints(width, height, segmentSize, rng);
    
    // Create segment map
    std::vector<int> segmentMap(width * height);
    createSegmentMap(width, height, seedGrid, segmentSize, segmentMap, 0, height);
    
    // Update cache
    g_segmentCache.width = width;
    g_segmentCache.height = height;
    g_segmentCache.segmentSize = segmentSize;
    g_segmentCache.seedGrid = seedGrid;
    g_segmentCache.segmentMap = segmentMap;
    
    return std::make_pair(segmentMap, seedGrid);
}

/**
 * Generate seed points in a grid pattern with random variation
 */
std::vector<std::vector<SeedPoint>> generateSeedPoints(
    int width, 
    int height, 
    int avgSegmentSize,
    std::mt19937& rng
) {
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
 * Create segment map using optimized Voronoi-like algorithm
 */
void createSegmentMap(
    int width,
    int height,
    const std::vector<std::vector<SeedPoint>>& seedGrid,
    int gridSpacing,
    std::vector<int>& segmentMap,
    int startY,
    int endY
) {
    for (int y = startY; y < endY; y++) {
        const int cellY = y / gridSpacing;
        const int minCellY = std::max(0, cellY - 1);
        const int maxCellY = std::min(static_cast<int>(seedGrid.size()) - 1, cellY + 1);
        
        for (int x = 0; x < width; x++) {
            const int cellX = x / gridSpacing;
            const int minCellX = std::max(0, cellX - 1);
            
            int minDistanceSquared = INT_MAX;
            int nearestSegment = 0;
            
            // Search in 3x3 neighborhood only (mathematically sufficient)
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

/**
 * Calculate segment colors using YUV to RGB conversion with parallel arrays
 */
void calculateSegmentColors(
    const uint8_t* yData,
    const uint8_t* uData,
    const uint8_t* vData,
    int width,
    int height,
    const std::vector<int>& segmentMap,
    std::vector<uint64_t>& redTotals,
    std::vector<uint64_t>& greenTotals,
    std::vector<uint64_t>& blueTotals,
    std::vector<uint32_t>& pixelCounts,
    size_t totalSegments
) {
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            const int pixelIndex = y * width + x;
            const int segmentId = segmentMap[pixelIndex];
            
            // Get YUV values
            const int yVal = yData[pixelIndex];
            const int uVal = uData[(y / 2) * (width / 2) + (x / 2)];
            const int vVal = vData[(y / 2) * (width / 2) + (x / 2)];
            
            // Convert YUV to RGB using optimized fixed-point conversion
            const uint32_t rgb = yuvToRgbFixed(yVal, uVal, vVal, false);
            
            // Add to parallel arrays
            if (segmentId < totalSegments) {
                redTotals[segmentId] += (rgb >> 16) & 0xFF;
                greenTotals[segmentId] += (rgb >> 8) & 0xFF;
                blueTotals[segmentId] += rgb & 0xFF;
                pixelCounts[segmentId]++;
            }
        }
    }
}

/**
 * Render final bitmap with segments and edges
 */
void renderStainedGlass(
    int width,
    int height,
    const std::vector<int>& segmentMap,
    const std::vector<uint32_t>& segmentColors,
    uint32_t* outputPixels,
    int edgeThickness,
    uint32_t edgeColor,
    int startY,
    int endY
) {
    // Fill segments with their average colors
    for (int y = startY; y < endY; y++) {
        for (int x = 0; x < width; x++) {
            const int pixelIndex = y * width + x;
            const int segmentId = segmentMap[pixelIndex];
            
            if (segmentId < segmentColors.size()) {
                outputPixels[pixelIndex] = segmentColors[segmentId] | 0xFF000000; // Add alpha
            } else {
                outputPixels[pixelIndex] = 0xFF000000; // Black fallback
            }
        }
    }
    
    // Draw edges between segments if edge thickness > 0
    if (edgeThickness > 0) {
        for (int y = startY; y < endY - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                const int currentSegment = segmentMap[y * width + x];
                
                // Check right neighbor
                if (segmentMap[y * width + (x + 1)] != currentSegment) {
                    for (int t = 0; t < edgeThickness && (x + t) < width; t++) {
                        outputPixels[y * width + (x + t)] = edgeColor | 0xFF000000;
                    }
                }
                
                // Check bottom neighbor
                if (segmentMap[(y + 1) * width + x] != currentSegment) {
                    for (int t = 0; t < edgeThickness && (y + t) < height; t++) {
                        outputPixels[(y + t) * width + x] = edgeColor | 0xFF000000;
                    }
                }
            }
        }
    }
}

/**
 * Main JNI function for stained glass effect processing
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_StainedGlassEffect_00024Companion_processStainedGlassNative(
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
    jintArray outputPixels,
    jint numThreads
) {
    // Get native arrays
    jbyte* yDataPtr = env->GetByteArrayElements(yData, nullptr);
    jbyte* uDataPtr = env->GetByteArrayElements(uData, nullptr);
    jbyte* vDataPtr = env->GetByteArrayElements(vData, nullptr);
    jint* outputPixelsPtr = env->GetIntArrayElements(outputPixels, nullptr);
    
    if (!yDataPtr || !uDataPtr || !vDataPtr || !outputPixelsPtr) {
        LOGE("Failed to get native arrays");
        return JNI_FALSE;
    }
    
    const uint8_t* yBytes = reinterpret_cast<const uint8_t*>(yDataPtr);
    const uint8_t* uBytes = reinterpret_cast<const uint8_t*>(uDataPtr);
    const uint8_t* vBytes = reinterpret_cast<const uint8_t*>(vDataPtr);
    uint32_t* outputBuffer = reinterpret_cast<uint32_t*>(outputPixelsPtr);
    
    try {
        // Get cached segment map or create new one
        auto segmentMapPair = getCachedSegmentMap(width, height, segmentSize);
        std::vector<int> segmentMap = segmentMapPair.first;
        std::vector<std::vector<SeedPoint>> seedGrid = segmentMapPair.second;
        
        if (seedGrid.empty()) {
            LOGE("Failed to get segment map");
            return JNI_FALSE;
        }
        
        // Calculate total number of segments
        size_t totalSegments = 0;
        for (const auto& row : seedGrid) {
            totalSegments += row.size();
        }
        
        // Calculate segment colors using parallel arrays
        std::vector<uint64_t> redTotals(totalSegments, 0);
        std::vector<uint64_t> greenTotals(totalSegments, 0);
        std::vector<uint64_t> blueTotals(totalSegments, 0);
        std::vector<uint32_t> pixelCounts(totalSegments, 0);
        
        // Single-threaded color calculation for simplicity
        calculateSegmentColors(yBytes, uBytes, vBytes, width, height, segmentMap,
                             redTotals, greenTotals, blueTotals, pixelCounts, totalSegments);
        
        // Convert totals to final colors with variation
        std::vector<uint32_t> segmentColors(totalSegments);
        std::mt19937 colorRng(width * height + segmentSize + 42); // Different seed for color variation
        std::uniform_int_distribution<int> variationDist(-static_cast<int>(colorVariation * 128), 
                                                        static_cast<int>(colorVariation * 128));
        
        for (int i = 0; i < totalSegments; i++) {
            uint32_t baseColor = 0x000000; // Black fallback
            
            if (pixelCounts[i] > 0) {
                uint32_t avgRed = static_cast<uint32_t>(redTotals[i] / pixelCounts[i]);
                uint32_t avgGreen = static_cast<uint32_t>(greenTotals[i] / pixelCounts[i]);
                uint32_t avgBlue = static_cast<uint32_t>(blueTotals[i] / pixelCounts[i]);
                baseColor = (avgRed << 16) | (avgGreen << 8) | avgBlue;
            }
            
            if (colorVariation > 0.0f && pixelCounts[i] > 0) {
                int r = std::clamp(static_cast<int>((baseColor >> 16) & 0xFF) + variationDist(colorRng), 0, 255);
                int g = std::clamp(static_cast<int>((baseColor >> 8) & 0xFF) + variationDist(colorRng), 0, 255);
                int b = std::clamp(static_cast<int>(baseColor & 0xFF) + variationDist(colorRng), 0, 255);
                segmentColors[i] = (r << 16) | (g << 8) | b;
            } else {
                segmentColors[i] = baseColor;
            }
        }
        
        // Render final bitmap (single-threaded for simplicity)
        renderStainedGlass(width, height, segmentMap, segmentColors, outputBuffer,
                         edgeThickness, static_cast<uint32_t>(edgeColor), 0, height);
    } catch (const std::exception& e) {
        LOGE("Exception in stained glass processing: %s", e.what());
        return JNI_FALSE;
    }
    
    // Release arrays
    env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(uData, uDataPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(vData, vDataPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(outputPixels, outputPixelsPtr, 0);
    
    return JNI_TRUE;
}
