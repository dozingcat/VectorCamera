#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cmath>
#include <thread>
#include <vector>

#define LOG_TAG "EdgeEffectNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Clamp function for keeping values in bounds
inline int clamp(int value, int min_val, int max_val) {
    return std::max(min_val, std::min(value, max_val));
}

// Calculate edge strength using Laplacian operator
inline int calculateEdgeStrength(
    const uint8_t* yData, 
    int x, 
    int y, 
    int width, 
    int height, 
    int yRowStride, 
    int multiplier
) {
    if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) {
        return 0;
    }
    
    // Get center pixel
    int center = yData[y * yRowStride + x];
    
    // Calculate sum of 8 surrounding pixels
    int surrounding = 
        yData[(y - 1) * yRowStride + (x - 1)] +
        yData[(y - 1) * yRowStride + x] +
        yData[(y - 1) * yRowStride + (x + 1)] +
        yData[y * yRowStride + (x - 1)] +
        yData[y * yRowStride + (x + 1)] +
        yData[(y + 1) * yRowStride + (x - 1)] +
        yData[(y + 1) * yRowStride + x] +
        yData[(y + 1) * yRowStride + (x + 1)];
    
    // Apply Laplacian operator
    int edge = 8 * center - surrounding;
    return clamp(multiplier * edge, 0, 255);
}

// Process a range of rows
void processRows(
    const uint8_t* yData,
    int yRowStride,
    int width,
    int height,
    int multiplier,
    const int* colorMap,
    int* outputPixels,
    int startRow,
    int endRow
) {
    for (int y = startRow; y < endRow; y++) {
        for (int x = 0; x < width; x++) {
            int edgeStrength = calculateEdgeStrength(yData, x, y, width, height, yRowStride, multiplier);
            outputPixels[y * width + x] = colorMap[edgeStrength];
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_EdgeEffect_00024Companion_processImageNativeFromYuvBytes(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jint width,
    jint height,
    jint multiplier,
    jintArray colorMap,
    jintArray outputPixels,
    jint numThreads
) {
    // Get native arrays
    jbyte* yDataPtr = env->GetByteArrayElements(yData, nullptr);
    jint* colorMapData = env->GetIntArrayElements(colorMap, nullptr);
    jint* outputPixelsData = env->GetIntArrayElements(outputPixels, nullptr);
    
    if (!yDataPtr || !colorMapData || !outputPixelsData) {
        LOGE("Failed to get native arrays");
        return;
    }
    
    // For YUV bytes, row stride equals width (no padding)
    int yRowStride = width;
    
    if (numThreads == 1) {
        // Single-threaded processing
        processRows(
            reinterpret_cast<const uint8_t*>(yDataPtr),
            yRowStride,
            width,
            height,
            multiplier,
            colorMapData,
            outputPixelsData,
            0,
            height
        );
    } else {
        // Multi-threaded processing
        std::vector<std::thread> threads;
        int rowsPerThread = height / numThreads;
        
        for (int i = 0; i < numThreads; i++) {
            int startRow = i * rowsPerThread;
            int endRow = (i == numThreads - 1) ? height : (i + 1) * rowsPerThread;
            
            threads.emplace_back(
                processRows,
                reinterpret_cast<const uint8_t*>(yDataPtr),
                yRowStride,
                width,
                height,
                multiplier,
                colorMapData,
                outputPixelsData,
                startRow,
                endRow
            );
        }
        
        // Wait for all threads to complete
        for (auto& thread : threads) {
            thread.join();
        }
    }
    
    // Release native arrays
    env->ReleaseByteArrayElements(yData, yDataPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(colorMap, colorMapData, JNI_ABORT);
    env->ReleaseIntArrayElements(outputPixels, outputPixelsData, 0);
} 