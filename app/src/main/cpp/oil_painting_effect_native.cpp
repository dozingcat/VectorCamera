#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <thread>
#include <vector>

#define LOG_TAG "OilPaintingEffectNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Force inline critical functions for performance
#define FORCE_INLINE __attribute__((always_inline)) inline

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

// Global brush pattern cache (thread-safe since read-only after initialization)
static std::vector<BrushPattern> brushPatterns;
static bool brushPatternsInitialized = false;

/**
 * Initialize pre-computed brush patterns for all possible radii.
 * Called once at startup to avoid runtime overhead.
 */
void initializeBrushPatterns() {
    if (brushPatternsInitialized) return;
    
    const int maxRadius = 20; // Support up to radius 20
    brushPatterns.resize(maxRadius + 1);
    
    for (int radius = 0; radius <= maxRadius; radius++) {
        BrushPattern& pattern = brushPatterns[radius];
        pattern.offsets.clear();
        
        if (radius == 0) {
            // Special case: radius 0 means just the center pixel
            pattern.offsets.emplace_back(0, 0);
            continue;
        }
        
        int radiusSquared = radius * radius;
        
        // Pre-compute all offsets within the circular brush
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distanceSquared = dx * dx + dy * dy;
                if (distanceSquared <= radiusSquared) {
                    pattern.offsets.emplace_back(static_cast<int16_t>(dx), static_cast<int16_t>(dy));
                }
            }
        }
        
        // Optimize for cache: sort offsets by memory access pattern (row-major order)
        std::sort(pattern.offsets.begin(), pattern.offsets.end(), 
                 [](const std::pair<int16_t, int16_t>& a, const std::pair<int16_t, int16_t>& b) {
                     if (a.second != b.second) return a.second < b.second; // Sort by Y first
                     return a.first < b.first; // Then by X
                 });
        
        // Compute incremental update patterns for horizontal sliding
        pattern.leftEdgeToRemove.clear();
        pattern.rightEdgeToAdd.clear();
        
        if (radius > 0) {
            for (const auto &p : pattern.offsets) {
                int dx = p.first;
                int dy = p.second;
                // If going left one pixel would put this point outside the radius,
                // the left-shifted point should be removed when sliding right.
                int leftDistanceSquared = (dx - 1) * (dx - 1) + dy * dy;
                if (leftDistanceSquared > radiusSquared) {
                    pattern.leftEdgeToRemove.emplace_back(dx - 1, dy);
                }
                // If going right one pixel would put this point outside the radius,
                // it's on the right edge and should be added when sliding right.
                int rightDistanceSquared = (dx + 1) * (dx + 1) + dy * dy;
                if (rightDistanceSquared > radiusSquared) {
                    pattern.rightEdgeToAdd.emplace_back(dx, dy);
                }
            }
        }
    }
    
    brushPatternsInitialized = true;
    LOGI("Initialized brush patterns for radii 0-%d with incremental updates", maxRadius);
}

/**
 * Fast YUV to RGB conversion optimized for oil painting effect.
 */
FORCE_INLINE uint32_t yuvToRgbQuantized(int y, int u, int v, int quantizationStep) {
    // Convert YUV to RGB using integer arithmetic
    int r = y + ((359 * (v - 128)) >> 8);
    int g = y - ((88 * (u - 128) + 183 * (v - 128)) >> 8);
    int b = y + ((454 * (u - 128)) >> 8);
    
    // Clamp values
    r = std::max(0, std::min(255, r));
    g = std::max(0, std::min(255, g));
    b = std::max(0, std::min(255, b));
    
    // Quantize colors for oil painting effect
    r = (r / quantizationStep) * quantizationStep;
    g = (g / quantizationStep) * quantizationStep;
    b = (b / quantizationStep) * quantizationStep;
    
    return (0xFF << 24) | (r << 16) | (g << 8) | b;
}

/**
 * Calculate brightness for contrast analysis.
 */
FORCE_INLINE double calculateBrightness(uint32_t pixel) {
    int r = (pixel >> 16) & 0xFF;
    int g = (pixel >> 8) & 0xFF;
    int b = pixel & 0xFF;
    return r * 0.299 + g * 0.587 + b * 0.114;
}

/**
 * Calculate adaptive brush size based on local contrast.
 */
int calculateAdaptiveBrushSize(
    const uint32_t* pixels,
    int centerX,
    int centerY,
    int width,
    int height,
    int maxBrushSize,
    float contrastSensitivity
) {
    const int sampleRadius = 3;
    double totalVariance = 0.0;
    int sampleCount = 0;
    
    uint32_t centerPixel = pixels[centerY * width + centerX];
    double centerBrightness = calculateBrightness(centerPixel);
    
    // Sample surrounding pixels to measure local contrast
    for (int dy = -sampleRadius; dy <= sampleRadius; dy++) {
        for (int dx = -sampleRadius; dx <= sampleRadius; dx++) {
            int sampleX = centerX + dx;
            int sampleY = centerY + dy;
            
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                uint32_t samplePixel = pixels[sampleY * width + sampleX];
                double sampleBrightness = calculateBrightness(samplePixel);
                
                double diff = sampleBrightness - centerBrightness;
                totalVariance += diff * diff;
                sampleCount++;
            }
        }
    }
    
    double avgVariance = (sampleCount > 0) ? totalVariance / sampleCount : 0.0;
    double normalizedVariance = std::min(1.0, avgVariance / (255.0 * 255.0));
    
    // High contrast = smaller brush, low contrast = larger brush
    double adaptiveFactor = 1.0 - std::min(0.8, normalizedVariance * contrastSensitivity);
    int adaptiveBrushSize = static_cast<int>(maxBrushSize * adaptiveFactor);
    
    return std::max(2, std::min(maxBrushSize, adaptiveBrushSize));
}

/**
 * Fast hash function for quantized colors.
 * Since colors are quantized, we can map them to a smaller space.
 */
FORCE_INLINE uint32_t hashQuantizedColor(uint32_t color) {
    // Extract quantized RGB components (assume 8-step quantization = 32 levels)
    // Take top 5 bits of each color channel for 15-bit hash (32K entries max)
    uint32_t r = (color >> 19) & 0x1F;  // Red: bits 23-19
    uint32_t g = (color >> 11) & 0x1F;  // Green: bits 15-11  
    uint32_t b = (color >> 3) & 0x1F;   // Blue: bits 7-3
    
    return (r << 10) | (g << 5) | b;    // 15-bit hash: 5+5+5 bits
}

enum ColorCountUpdateType {Full, Incremental};

/**
 * Incrementally update color counts when sliding brush horizontally.
 * Much faster than recalculating entire brush area.
 */
void updateColorCounts(
    const uint32_t* pixels,
    int centerX,
    int centerY,
    int width,
    int height,
    int radius,
    std::vector<uint16_t>& colorCounts,
    std::vector<uint32_t>& usedColors,
    std::vector<uint32_t>& colorSamples,
    ColorCountUpdateType updateType
) {
    if (!brushPatternsInitialized) {
        initializeBrushPatterns();
    }
    
    radius = std::max(0, std::min(radius, static_cast<int>(brushPatterns.size()) - 1));
    const BrushPattern& pattern = brushPatterns[radius];
    
    if (updateType == ColorCountUpdateType::Full) {
        // Initialize with full brush pattern
        usedColors.clear();
        colorSamples.clear();
        
        for (const auto& offset : pattern.offsets) {
            int sampleX = centerX + offset.first;
            int sampleY = centerY + offset.second;
            
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                uint32_t samplePixel = pixels[sampleY * width + sampleX];
                uint32_t colorHash = hashQuantizedColor(samplePixel);
                
                if (colorCounts[colorHash] == 0) {
                    usedColors.push_back(colorHash);
                    colorSamples.push_back(samplePixel);
                }
                colorCounts[colorHash]++;
            }
        }
    } else {
        // Remove left edge pixels
        for (const auto& offset : pattern.leftEdgeToRemove) {
            int sampleX = centerX + offset.first;
            int sampleY = centerY + offset.second;
            
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                uint32_t samplePixel = pixels[sampleY * width + sampleX];
                uint32_t colorHash = hashQuantizedColor(samplePixel);
                
                if (colorCounts[colorHash] > 0) {
                    colorCounts[colorHash]--;
                }
            }
        }
        
        // Add right edge pixels
        for (const auto& offset : pattern.rightEdgeToAdd) {
            int sampleX = centerX + offset.first;
            int sampleY = centerY + offset.second;
            
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                uint32_t samplePixel = pixels[sampleY * width + sampleX];
                uint32_t colorHash = hashQuantizedColor(samplePixel);
                
                if (colorCounts[colorHash] == 0) {
                    usedColors.push_back(colorHash);
                    colorSamples.push_back(samplePixel);
                }
                colorCounts[colorHash]++;
            }
        }
    }
}

/**
 * Find dominant color using current color counts (after incremental update).
 * Also maintains a mapping from hash to actual color.
 */
uint32_t findDominantColorFromCounts(
    const std::vector<uint16_t>& colorCounts,
    const std::vector<uint32_t>& usedColors,
    const std::vector<uint32_t>& colorSamples,
    uint32_t fallbackColor
) {
    uint32_t dominantColor = fallbackColor;
    int maxCount = 0;
    
    // Find color with highest count
    for (size_t i = 0; i < usedColors.size(); i++) {
        uint32_t colorHash = usedColors[i];
        if (colorCounts[colorHash] > maxCount) {
            maxCount = colorCounts[colorHash];
            dominantColor = colorSamples[i]; // Use stored sample
        }
    }
    
    return dominantColor;
}

/**
 * Process a range of rows for the oil painting effect.
 */
void processOilPaintingRows(
    const uint8_t* yData,
    const uint8_t* uData,
    const uint8_t* vData,
    int width,
    int height,
    int brushSize,
    int levels,
    float contrastSensitivity,
    uint32_t* outputPixels,
    int startRow,
    int endRow
) {
    const int uvWidth = (width + 1) / 2;
    const int quantizationStep = 256 / levels;
    
    // First pass: Convert YUV to quantized RGB
    std::vector<uint32_t> rgbPixels(width * height);
    
    for (int y = startRow; y < endRow; y++) {
        for (int x = 0; x < width; x++) {
            int pixelIndex = y * width + x;
            
            // Get Y value
            int yy = yData[pixelIndex];
            
            // Get U and V values (subsampled)
            int uvX = x / 2;
            int uvY = y / 2;
            int uvIndex = uvY * uvWidth + uvX;
            int u = uData[uvIndex];
            int v = vData[uvIndex];
            
            rgbPixels[pixelIndex] = yuvToRgbQuantized(yy, u, v, quantizationStep);
        }
    }
    
    // Thread-local storage for incremental updates
    std::vector<uint16_t> colorCounts(32768, 0);
    std::vector<uint32_t> usedColors;
    std::vector<uint32_t> colorSamples;
    
    // Second pass: Apply oil painting effect with incremental brush sliding
    for (int y = startRow; y < endRow; y++) {
        // Clear color counts at start of each row
        for (uint32_t colorHash : usedColors) {
            colorCounts[colorHash] = 0;
        }
        usedColors.clear();
        colorSamples.clear();
        
        int previousBrushSize = -1;
        
        for (int x = 0; x < width; x++) {
            int pixelIndex = y * width + x;
            
            // Calculate adaptive brush size based on local contrast
            int localBrushSize = calculateAdaptiveBrushSize(
                rgbPixels.data(), x, y, width, height, brushSize, contrastSensitivity
            );
            
            // Use incremental updates if brush size hasn't changed
            if (localBrushSize == previousBrushSize && x > 0) {
                // Incremental update: slide brush horizontally
                updateColorCounts(
                        rgbPixels.data(), x, y, width, height, localBrushSize,
                        colorCounts, usedColors, colorSamples, ColorCountUpdateType::Incremental
                );
            } else {
                // Full recalculation: brush size changed or first pixel in row
                if (previousBrushSize != -1) {
                    // Clear previous brush data
                    for (uint32_t colorHash : usedColors) {
                        colorCounts[colorHash] = 0;
                    }
                    usedColors.clear();
                    colorSamples.clear();
                }

                updateColorCounts(
                        rgbPixels.data(), x, y, width, height, localBrushSize,
                        colorCounts, usedColors, colorSamples, ColorCountUpdateType::Full
                );
            }
            
            // Find dominant color using current counts
            uint32_t dominantColor = findDominantColorFromCounts(
                colorCounts, usedColors, colorSamples, rgbPixels[pixelIndex]
            );
            
            outputPixels[pixelIndex] = dominantColor;
            previousBrushSize = localBrushSize;
        }
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_OilPaintingEffect_00024Companion_processImageNativeFromPlanes(
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
    jint numThreads
) {
    // Get native arrays
    jbyte* yDataPtr = env->GetByteArrayElements(yData, nullptr);
    jbyte* uDataPtr = env->GetByteArrayElements(uData, nullptr);
    jbyte* vDataPtr = env->GetByteArrayElements(vData, nullptr);
    
    if (!yDataPtr || !uDataPtr || !vDataPtr) {
        LOGE("Failed to get native arrays");
        return nullptr;
    }
    
    // Create output array
    jintArray outputArray = env->NewIntArray(width * height);
    if (!outputArray) {
        LOGE("Failed to create output array");
        return nullptr;
    }
    
    jint* outputPixels = env->GetIntArrayElements(outputArray, nullptr);
    if (!outputPixels) {
        LOGE("Failed to get output array elements");
        return nullptr;
    }
    
    try {
        // Initialize brush patterns on first use
        initializeBrushPatterns();
        
        if (numThreads == 1) {
            // Single-threaded processing
            processOilPaintingRows(
                reinterpret_cast<const uint8_t*>(yDataPtr),
                reinterpret_cast<const uint8_t*>(uDataPtr),
                reinterpret_cast<const uint8_t*>(vDataPtr),
                width,
                height,
                brushSize,
                levels,
                contrastSensitivity,
                reinterpret_cast<uint32_t*>(outputPixels),
                0,
                height
            );
        } else {
            // Multi-threaded processing
            std::vector<std::thread> threads;
            threads.reserve(numThreads);
            
            int rowsPerThread = height / numThreads;
            
            for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
                int startRow = threadIndex * rowsPerThread;
                int endRow = (threadIndex == numThreads - 1) ? height : (threadIndex + 1) * rowsPerThread;
                
                threads.emplace_back([=]() {
                    processOilPaintingRows(
                        reinterpret_cast<const uint8_t*>(yDataPtr),
                        reinterpret_cast<const uint8_t*>(uDataPtr),
                        reinterpret_cast<const uint8_t*>(vDataPtr),
                        width,
                        height,
                        brushSize,
                        levels,
                        contrastSensitivity,
                        reinterpret_cast<uint32_t*>(outputPixels),
                        startRow,
                        endRow
                    );
                });
            }
            
            // Wait for all threads to complete
            for (auto& thread : threads) {
                thread.join();
            }
        }
        
        LOGI("Oil painting effect processed successfully: %dx%d, brush=%d, levels=%d, threads=%d", 
             width, height, brushSize, levels, numThreads);
        
    } catch (const std::exception& e) {
        LOGE("Exception during oil painting processing: %s", e.what());
        env->ReleaseIntArrayElements(outputArray, outputPixels, JNI_ABORT);
        env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
        env->ReleaseByteArrayElements(uData, uDataPtr, JNI_ABORT);
        env->ReleaseByteArrayElements(vData, vDataPtr, JNI_ABORT);
        return nullptr;
    }
    
    // Release arrays
    env->ReleaseIntArrayElements(outputArray, outputPixels, 0);
    env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(uData, uDataPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(vData, vDataPtr, JNI_ABORT);
    
    return outputArray;
}
