#include <jni.h>
#include <android/log.h>
#include <thread>
#include <vector>
#include <algorithm>
#include <cmath>

#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

#define LOG_TAG "Convolve3x3EffectNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Force inline for critical functions
#define FORCE_INLINE __attribute__((always_inline)) inline



// Fast 3x3 convolution with unrolled loops and optimized boundary handling
FORCE_INLINE void processConvolutionRows(
    int startY, 
    int endY, 
    int width, 
    int height, 
    const unsigned char* __restrict__ yData, 
    unsigned char* __restrict__ convolvedData, 
    const float* __restrict__ coefficients
) {
    const float c0 = coefficients[0], c1 = coefficients[1], c2 = coefficients[2];
    const float c3 = coefficients[3], c4 = coefficients[4], c5 = coefficients[5];
    const float c6 = coefficients[6], c7 = coefficients[7], c8 = coefficients[8];
    
    for (int y = startY; y < endY; y++) {
        const unsigned char* rowPtr = yData + y * width;
        unsigned char* outPtr = convolvedData + y * width;
        
        // Prefetch next row for better cache performance
        if (y + 1 < endY) {
            __builtin_prefetch(yData + (y + 1) * width, 0, 3);
        }
        
        // Handle different regions separately for better optimization
        
        // Left edge (x = 0)
        if (width > 0) {
            const unsigned char* prevRow = (y > 0) ? rowPtr - width : rowPtr;
            const unsigned char* nextRow = (y < height - 1) ? rowPtr + width : rowPtr;
            
            float sum = c0 * prevRow[0] + c1 * prevRow[0] + c2 * prevRow[1] +
                       c3 * rowPtr[0] + c4 * rowPtr[0] + c5 * rowPtr[1] +
                       c6 * nextRow[0] + c7 * nextRow[0] + c8 * nextRow[1];
            
            int result = static_cast<int>(sum + 0.5f); // Fast rounding
            outPtr[0] = static_cast<unsigned char>(result < 0 ? 0 : (result > 255 ? 255 : result));
        }
        
        // Middle region (x = 1 to width-2) - no boundary checks needed
        for (int x = 1; x < width - 1; x++) {
            const unsigned char* prevRow = (y > 0) ? rowPtr - width + x : rowPtr + x;
            const unsigned char* currRow = rowPtr + x;
            const unsigned char* nextRow = (y < height - 1) ? rowPtr + width + x : rowPtr + x;
            
            // Unrolled 3x3 convolution
            float sum = c0 * prevRow[-1] + c1 * prevRow[0] + c2 * prevRow[1] +
                       c3 * currRow[-1] + c4 * currRow[0] + c5 * currRow[1] +
                       c6 * nextRow[-1] + c7 * nextRow[0] + c8 * nextRow[1];
            
            int result = static_cast<int>(sum + 0.5f); // Fast rounding
            outPtr[x] = static_cast<unsigned char>(result < 0 ? 0 : (result > 255 ? 255 : result));
        }
        
        // Right edge (x = width-1)
        if (width > 1) {
            int x = width - 1;
            const unsigned char* prevRow = (y > 0) ? rowPtr - width + x : rowPtr + x;
            const unsigned char* currRow = rowPtr + x;
            const unsigned char* nextRow = (y < height - 1) ? rowPtr + width + x : rowPtr + x;
            
            float sum = c0 * prevRow[-1] + c1 * prevRow[0] + c2 * prevRow[0] +
                       c3 * currRow[-1] + c4 * currRow[0] + c5 * currRow[0] +
                       c6 * nextRow[-1] + c7 * nextRow[0] + c8 * nextRow[0];
            
            int result = static_cast<int>(sum + 0.5f); // Fast rounding
            outPtr[x] = static_cast<unsigned char>(result < 0 ? 0 : (result > 255 ? 255 : result));
        }
    }
}

// Map convolved brightness values to colors using color map
FORCE_INLINE void processColorMappingRows(
    int startIndex,
    int endIndex,
    const unsigned char* __restrict__ convolvedData,
    int* __restrict__ pixels,
    const int* __restrict__ colorMap
) {
    // Process in chunks for better cache performance
    for (int i = startIndex; i < endIndex; i++) {
        // Direct lookup without masking since convolvedData is already in range 0-255
        pixels[i] = colorMap[convolvedData[i]];
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_Convolve3x3EffectKotlin_00024Companion_processImageNativeFromYuvBytes(
    JNIEnv* env, 
    jobject /* this */, 
    jbyteArray yuvBytes_, 
    jint width, 
    jint height,
    jfloatArray coefficients_,
    jintArray colorMap_,
    jint numThreads
) {
    // Get YUV data
    jbyte* yuvBytes = env->GetByteArrayElements(yuvBytes_, NULL);
    if (yuvBytes == NULL) {
        return NULL;
    }

    // Get coefficients
    jfloat* coefficients = env->GetFloatArrayElements(coefficients_, NULL);
    if (coefficients == NULL) {
        env->ReleaseByteArrayElements(yuvBytes_, yuvBytes, 0);
        return NULL;
    }

    // Get color map
    jint* colorMap = env->GetIntArrayElements(colorMap_, NULL);
    if (colorMap == NULL) {
        env->ReleaseByteArrayElements(yuvBytes_, yuvBytes, 0);
        env->ReleaseFloatArrayElements(coefficients_, coefficients, 0);
        return NULL;
    }

    const unsigned char* yuvData = reinterpret_cast<const unsigned char*>(yuvBytes);
    
    // Calculate Y plane size
    int ySize = width * height;
    const unsigned char* yData = yuvData;  // Y plane is at the beginning

    // Create intermediate array for convolved data
    unsigned char* convolvedData = new unsigned char[ySize];

    // Apply 3x3 convolution with multi-threading
    if (numThreads == 1) {
        processConvolutionRows(0, height, width, height, yData, convolvedData, coefficients);
    } else {
        std::vector<std::thread> threads;
        int rowsPerThread = height / numThreads;
        
        for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
            int startY = threadIndex * rowsPerThread;
            int endY = (threadIndex == numThreads - 1) ? height : (threadIndex + 1) * rowsPerThread;
            
            threads.emplace_back(processConvolutionRows, startY, endY, width, height, yData, convolvedData, coefficients);
        }
        
        // Wait for all threads to complete
        for (auto& thread : threads) {
            thread.join();
        }
    }

    // Create output array
    jintArray result = env->NewIntArray(width * height);
    if (result == NULL) {
        delete[] convolvedData;
        env->ReleaseByteArrayElements(yuvBytes_, yuvBytes, 0);
        env->ReleaseFloatArrayElements(coefficients_, coefficients, 0);
        env->ReleaseIntArrayElements(colorMap_, colorMap, 0);
        return NULL;
    }

    jint* pixels = env->GetIntArrayElements(result, NULL);
    if (pixels == NULL) {
        delete[] convolvedData;
        env->ReleaseByteArrayElements(yuvBytes_, yuvBytes, 0);
        env->ReleaseFloatArrayElements(coefficients_, coefficients, 0);
        env->ReleaseIntArrayElements(colorMap_, colorMap, 0);
        return NULL;
    }

    // Map convolved values to colors with multi-threading
    if (numThreads == 1) {
        processColorMappingRows(0, ySize, convolvedData, pixels, colorMap);
    } else {
        std::vector<std::thread> threads;
        int pixelsPerThread = ySize / numThreads;
        
        for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
            int startIndex = threadIndex * pixelsPerThread;
            int endIndex = (threadIndex == numThreads - 1) ? ySize : (threadIndex + 1) * pixelsPerThread;
            
            threads.emplace_back(processColorMappingRows, startIndex, endIndex, convolvedData, pixels, colorMap);
        }
        
        // Wait for all threads to complete
        for (auto& thread : threads) {
            thread.join();
        }
    }

    // Clean up
    delete[] convolvedData;
    env->ReleaseIntArrayElements(result, pixels, 0);
    env->ReleaseByteArrayElements(yuvBytes_, yuvBytes, 0);
    env->ReleaseFloatArrayElements(coefficients_, coefficients, 0);
    env->ReleaseIntArrayElements(colorMap_, colorMap, 0);

    return result;
} 