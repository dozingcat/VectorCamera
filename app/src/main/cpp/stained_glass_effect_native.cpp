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
 * Segment color accumulator for multi-threaded processing
 */
struct SegmentAccumulator {
    std::atomic<uint64_t> totalRed{0};
    std::atomic<uint64_t> totalGreen{0};
    std::atomic<uint64_t> totalBlue{0};
    std::atomic<uint32_t> pixelCount{0};
    
    void addColor(uint32_t rgb) {
        totalRed.fetch_add((rgb >> 16) & 0xFF, std::memory_order_relaxed);
        totalGreen.fetch_add((rgb >> 8) & 0xFF, std::memory_order_relaxed);
        totalBlue.fetch_add(rgb & 0xFF, std::memory_order_relaxed);
        pixelCount.fetch_add(1, std::memory_order_relaxed);
    }
    
    uint32_t getAverageColor() const {
        uint32_t count = pixelCount.load(std::memory_order_relaxed);
        if (count == 0) return 0x000000;
        
        uint32_t avgRed = static_cast<uint32_t>(totalRed.load(std::memory_order_relaxed) / count);
        uint32_t avgGreen = static_cast<uint32_t>(totalGreen.load(std::memory_order_relaxed) / count);
        uint32_t avgBlue = static_cast<uint32_t>(totalBlue.load(std::memory_order_relaxed) / count);
        
        return (avgRed << 16) | (avgGreen << 8) | avgBlue;
    }
};

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
 * Calculate segment colors using YUV to RGB conversion
 */
void calculateSegmentColors(
    const uint8_t* yData,
    const uint8_t* uData,
    const uint8_t* vData,
    int width,
    int height,
    const std::vector<int>& segmentMap,
    std::vector<SegmentAccumulator>& segmentAccumulators,
    int startY,
    int endY
) {
    for (int y = startY; y < endY; y++) {
        for (int x = 0; x < width; x++) {
            const int pixelIndex = y * width + x;
            const int segmentId = segmentMap[pixelIndex];
            
            // Get YUV values
            const int yVal = yData[pixelIndex];
            const int uVal = uData[(y / 2) * (width / 2) + (x / 2)];
            const int vVal = vData[(y / 2) * (width / 2) + (x / 2)];
            
            // Convert YUV to RGB using optimized fixed-point conversion
            const uint32_t rgb = yuvToRgbFixed(yVal, uVal, vVal, false);
            
            // Add to segment accumulator (thread-safe)
            if (segmentId < segmentAccumulators.size()) {
                segmentAccumulators[segmentId].addColor(rgb);
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
        // Generate seed points with deterministic randomization (using image dimensions as seed)
        std::mt19937 rng(width * height + segmentSize);
        auto seedGrid = generateSeedPoints(width, height, segmentSize, rng);
        
        if (seedGrid.empty()) {
            LOGE("Failed to generate seed points");
            return JNI_FALSE;
        }
        
        // Calculate total number of segments
        int totalSegments = 0;
        for (const auto& row : seedGrid) {
            totalSegments += row.size();
        }
        
        LOGI("Generated %d segments in %dx%d grid", totalSegments, (int)seedGrid.size(), 
             seedGrid.empty() ? 0 : (int)seedGrid[0].size());
        
        // Create segment map
        std::vector<int> segmentMap(width * height);
        const int gridSpacing = segmentSize;
        
        if (numThreads == 1) {
            // Single-threaded segment map creation
            createSegmentMap(width, height, seedGrid, gridSpacing, segmentMap, 0, height);
        } else {
            // Multi-threaded segment map creation
            std::vector<std::thread> threads;
            const int rowsPerThread = height / numThreads;
            
            for (int i = 0; i < numThreads; i++) {
                const int startY = i * rowsPerThread;
                const int endY = (i == numThreads - 1) ? height : (i + 1) * rowsPerThread;
                
                threads.emplace_back([&, startY, endY]() {
                    createSegmentMap(width, height, seedGrid, gridSpacing, segmentMap, startY, endY);
                });
            }
            
            for (auto& thread : threads) {
                thread.join();
            }
        }
        
        // Calculate segment colors
        std::vector<SegmentAccumulator> segmentAccumulators(totalSegments);
        
        if (numThreads == 1) {
            // Single-threaded color calculation
            calculateSegmentColors(yBytes, uBytes, vBytes, width, height, segmentMap, 
                                 segmentAccumulators, 0, height);
        } else {
            // Multi-threaded color calculation
            std::vector<std::thread> threads;
            const int rowsPerThread = height / numThreads;
            
            for (int i = 0; i < numThreads; i++) {
                const int startY = i * rowsPerThread;
                const int endY = (i == numThreads - 1) ? height : (i + 1) * rowsPerThread;
                
                threads.emplace_back([&, startY, endY]() {
                    calculateSegmentColors(yBytes, uBytes, vBytes, width, height, segmentMap,
                                         segmentAccumulators, startY, endY);
                });
            }
            
            for (auto& thread : threads) {
                thread.join();
            }
        }
        
        // Convert accumulators to final colors with variation
        std::vector<uint32_t> segmentColors(totalSegments);
        std::uniform_int_distribution<int> variationDist(-static_cast<int>(colorVariation * 128), 
                                                        static_cast<int>(colorVariation * 128));
        
        for (int i = 0; i < totalSegments; i++) {
            uint32_t baseColor = segmentAccumulators[i].getAverageColor();
            
            if (colorVariation > 0.0f) {
                int r = std::clamp(static_cast<int>((baseColor >> 16) & 0xFF) + variationDist(rng), 0, 255);
                int g = std::clamp(static_cast<int>((baseColor >> 8) & 0xFF) + variationDist(rng), 0, 255);
                int b = std::clamp(static_cast<int>(baseColor & 0xFF) + variationDist(rng), 0, 255);
                segmentColors[i] = (r << 16) | (g << 8) | b;
            } else {
                segmentColors[i] = baseColor;
            }
        }
        
        // Render final bitmap
        if (numThreads == 1) {
            // Single-threaded rendering
            renderStainedGlass(width, height, segmentMap, segmentColors, outputBuffer,
                             edgeThickness, static_cast<uint32_t>(edgeColor), 0, height);
        } else {
            // Multi-threaded rendering
            std::vector<std::thread> threads;
            const int rowsPerThread = height / numThreads;
            
            for (int i = 0; i < numThreads; i++) {
                const int startY = i * rowsPerThread;
                const int endY = (i == numThreads - 1) ? height : (i + 1) * rowsPerThread;
                
                threads.emplace_back([&, startY, endY]() {
                    renderStainedGlass(width, height, segmentMap, segmentColors, outputBuffer,
                                     edgeThickness, static_cast<uint32_t>(edgeColor), startY, endY);
                });
            }
            
            for (auto& thread : threads) {
                thread.join();
            }
        }
        
        LOGI("Successfully processed stained glass effect");
        
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
