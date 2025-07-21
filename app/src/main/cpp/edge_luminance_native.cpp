#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <thread>
#include <vector>
#include "yuv.h"

#define LOG_TAG "EdgeLuminanceNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)



// Fast edge detection using Laplacian operator - optimized for cache efficiency
inline int calculateEdgeStrength(
    const uint8_t* yData, 
    int x, int y, 
    int width, int height, 
    int multiplier
) {
    if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) {
        return 0;
    }

    // Pre-calculate row offsets for better cache access
    const uint8_t* prevRow = yData + (y - 1) * width;
    const uint8_t* currRow = yData + y * width;
    const uint8_t* nextRow = yData + (y + 1) * width;
    
    const int center = currRow[x];
    
    // Get surrounding 8 pixels for Laplacian operator
    const int surroundingSum = 
        prevRow[x - 1] + prevRow[x] + prevRow[x + 1] +
        currRow[x - 1] + currRow[x + 1] +
        nextRow[x - 1] + nextRow[x] + nextRow[x + 1];

    const int edge = 8 * center - surroundingSum;
    // Fast clamp using conditional
    const int result = multiplier * edge;
    return (result < 0) ? 0 : (result > 255) ? 255 : result;
}

// Native implementation of processRows
extern "C" JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_EdgeLuminanceEffectKotlin_processRowsNative(
    JNIEnv* env,
    jobject /* this */,
    jint startY,
    jint endY,
    jint width,
    jint height,
    jint multiplier,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint uvWidth,
    jintArray pixels
) {
    // Use GetPrimitiveArrayCritical for better performance (no copying)
    jbyte* yBytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(yData, nullptr));
    jbyte* uBytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(uData, nullptr));
    jbyte* vBytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(vData, nullptr));
    jint* pixelArray = static_cast<jint*>(env->GetPrimitiveArrayCritical(pixels, nullptr));

    if (!yBytes || !uBytes || !vBytes || !pixelArray) {
        LOGI("Failed to get array elements");
        return;
    }

    // Cast to unsigned for easier arithmetic
    const uint8_t* yPtr = reinterpret_cast<const uint8_t*>(yBytes);
    const uint8_t* uPtr = reinterpret_cast<const uint8_t*>(uBytes);
    const uint8_t* vPtr = reinterpret_cast<const uint8_t*>(vBytes);

    // Process each row in the assigned range
    for (int y = startY; y < endY; y++) {
        for (int x = 0; x < width; x++) {
            const int pixelIndex = y * width + x;

            // Calculate edge strength
            const int edgeStrength = calculateEdgeStrength(
                yPtr, x, y, width, height, multiplier);

            // Get U and V values (subsampled)
            const int uvX = x / 2;
            const int uvY = y / 2;
            const int uvIndex = uvY * uvWidth + uvX;
            const int u = uPtr[uvIndex];
            const int v = vPtr[uvIndex];

            // Convert YUV to RGB using edge strength as Y value
            pixelArray[pixelIndex] = static_cast<jint>(YuvUtils::yuvToRgbFixed(edgeStrength, u, v, true));
        }
    }

    // Release array elements
    env->ReleasePrimitiveArrayCritical(yData, yBytes, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(uData, uBytes, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(vData, vBytes, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(pixels, pixelArray, 0);
}

// Optimized version that handles all threading internally in C++
extern "C" JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_EdgeLuminanceEffectKotlin_processImageNative(
    JNIEnv* env,
    jobject /* this */,
    jint width,
    jint height,
    jint multiplier,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint uvWidth,
    jintArray pixels,
    jint numThreads
) {
    // Use GetPrimitiveArrayCritical for better performance
    jbyte* yBytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(yData, nullptr));
    jbyte* uBytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(uData, nullptr));
    jbyte* vBytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(vData, nullptr));
    jint* pixelArray = static_cast<jint*>(env->GetPrimitiveArrayCritical(pixels, nullptr));

    if (!yBytes || !uBytes || !vBytes || !pixelArray) {
        LOGI("Failed to get array elements");
        return;
    }

    // Cast to unsigned for easier arithmetic
    const uint8_t* yPtr = reinterpret_cast<const uint8_t*>(yBytes);
    const uint8_t* uPtr = reinterpret_cast<const uint8_t*>(uBytes);
    const uint8_t* vPtr = reinterpret_cast<const uint8_t*>(vBytes);

    if (numThreads <= 1) {
        // Single-threaded processing
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                const int pixelIndex = y * width + x;
                const int edgeStrength = calculateEdgeStrength(yPtr, x, y, width, height, multiplier);
                const int uvX = x / 2;
                const int uvY = y / 2;
                const int uvIndex = uvY * uvWidth + uvX;
                const int u = uPtr[uvIndex];
                const int v = vPtr[uvIndex];
                pixelArray[pixelIndex] = static_cast<jint>(YuvUtils::yuvToRgbFixed(edgeStrength, u, v, true));
            }
        }
    } else {
        // Multi-threaded processing in C++
        std::vector<std::thread> threads;
        const int rowsPerThread = height / numThreads;
        
        for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
            const int startY = threadIndex * rowsPerThread;
            const int endY = (threadIndex == numThreads - 1) ? height : (threadIndex + 1) * rowsPerThread;
            
            threads.emplace_back([=]() {
                for (int y = startY; y < endY; y++) {
                    for (int x = 0; x < width; x++) {
                        const int pixelIndex = y * width + x;
                        const int edgeStrength = calculateEdgeStrength(yPtr, x, y, width, height, multiplier);
                        const int uvX = x / 2;
                        const int uvY = y / 2;
                        const int uvIndex = uvY * uvWidth + uvX;
                        const int u = uPtr[uvIndex];
                        const int v = vPtr[uvIndex];
                        pixelArray[pixelIndex] = static_cast<jint>(YuvUtils::yuvToRgbFixed(edgeStrength, u, v, true));
                    }
                }
            });
        }
        
        // Wait for all threads to complete
        for (auto& thread : threads) {
            thread.join();
        }
    }

    // Release array elements
    env->ReleasePrimitiveArrayCritical(yData, yBytes, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(uData, uBytes, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(vData, vBytes, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(pixels, pixelArray, 0);
}

// Function to check if native implementation is available
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_EdgeLuminanceEffectKotlin_isNativeAvailable(
    JNIEnv* env,
    jobject /* this */
) {
    return JNI_TRUE;
} 