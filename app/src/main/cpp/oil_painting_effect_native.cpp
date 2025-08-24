#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <thread>
#include <vector>
#include <unordered_map>

#define LOG_TAG "OilPaintingEffectNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Force inline critical functions for performance
#define FORCE_INLINE __attribute__((always_inline)) inline

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
 * Find the most dominant color in a circular neighborhood using efficient color counting.
 */
uint32_t findDominantColorInRadius(
    const uint32_t* pixels,
    int centerX,
    int centerY,
    int width,
    int height,
    int radius
) {
    std::unordered_map<uint32_t, int> colorCounts;
    colorCounts.reserve(64); // Reserve space for common case
    
    int radiusSquared = radius * radius;
    
    // Sample pixels in circular area
    for (int dy = -radius; dy <= radius; dy++) {
        for (int dx = -radius; dx <= radius; dx++) {
            int distanceSquared = dx * dx + dy * dy;
            if (distanceSquared <= radiusSquared) {
                int sampleX = centerX + dx;
                int sampleY = centerY + dy;
                
                if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                    uint32_t samplePixel = pixels[sampleY * width + sampleX];
                    colorCounts[samplePixel]++;
                }
            }
        }
    }
    
    // Find most frequent color
    uint32_t dominantColor = pixels[centerY * width + centerX]; // fallback
    int maxCount = 0;
    
    for (const auto& pair : colorCounts) {
        if (pair.second > maxCount) {
            maxCount = pair.second;
            dominantColor = pair.first;
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
    
    // Second pass: Apply oil painting effect
    for (int y = startRow; y < endRow; y++) {
        for (int x = 0; x < width; x++) {
            int pixelIndex = y * width + x;
            
            // Calculate adaptive brush size based on local contrast
            int localBrushSize = calculateAdaptiveBrushSize(
                rgbPixels.data(), x, y, width, height, brushSize, contrastSensitivity
            );
            
            // Find dominant color in circular neighborhood
            uint32_t dominantColor = findDominantColorInRadius(
                rgbPixels.data(), x, y, width, height, localBrushSize
            );
            
            outputPixels[pixelIndex] = dominantColor;
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
