#include <jni.h>
#include <android/log.h>
#include <thread>
#include <vector>
#include <algorithm>
#include <cmath>
#include "yuv.h"

#define LOG_TAG "PermuteColorEffectNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Extract color component based on ColorComponentSource mapping
inline int extractComponent(int r, int g, int b, int source) {
    switch (source) {
        case 1: return r;     // RED
        case 2: return g;     // GREEN
        case 3: return b;     // BLUE
        case 0: return 0;     // MIN
        case -1: return 255;  // MAX
        default: return 0;
    }
}

// Process rows function for multi-threading
void processRows(
    int startY, 
    int endY, 
    int width, 
    int height, 
    const unsigned char* yData, 
    const unsigned char* uData, 
    const unsigned char* vData, 
    int uvWidth, 
    int* pixels,
    int redSource,
    int greenSource,
    int blueSource,
    bool flipUV
) {
    for (int y = startY; y < endY; y++) {
        for (int x = 0; x < width; x++) {
            int pixelIndex = y * width + x;

            // Get Y value
            int yy = yData[pixelIndex] & 0xFF;

            // Get U and V values (subsampled)
            int uvX = x / 2;
            int uvY = y / 2;
            int uvIndex = uvY * uvWidth + uvX;
            int u = uData[uvIndex] & 0xFF;
            int v = vData[uvIndex] & 0xFF;

            // flipUV rotates the color 180 degrees in UV space.
            int uu = flipUV ? (-u & 0xFF) : u;
            int vv = flipUV ? (-v & 0xFF) : v;

            // Convert YUV to RGB
            int rgb = YuvUtils::yuvToRgb(yy, uu, vv, false);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            // Extract color components based on source mapping
            int outputR = extractComponent(r, g, b, redSource);
            int outputG = extractComponent(r, g, b, greenSource);
            int outputB = extractComponent(r, g, b, blueSource);

            // Create final ARGB pixel
            pixels[pixelIndex] = (255 << 24) | (outputR << 16) | (outputG << 8) | outputB;
        }
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_PermuteColorEffect_00024Companion_processImageNativeFromPlanes(
    JNIEnv* env, 
    jobject /* this */, 
    jbyteArray yData_, 
    jbyteArray uData_, 
    jbyteArray vData_, 
    jint width, 
    jint height,
    jint redSource,
    jint greenSource,
    jint blueSource,
    jboolean flipUV,
    jint numThreads
) {
    // Get individual plane data
    jbyte* yBytes = env->GetByteArrayElements(yData_, NULL);
    jbyte* uBytes = env->GetByteArrayElements(uData_, NULL);
    jbyte* vBytes = env->GetByteArrayElements(vData_, NULL);
    
    if (yBytes == NULL || uBytes == NULL || vBytes == NULL) {
        if (yBytes) env->ReleaseByteArrayElements(yData_, yBytes, 0);
        if (uBytes) env->ReleaseByteArrayElements(uData_, uBytes, 0);
        if (vBytes) env->ReleaseByteArrayElements(vData_, vBytes, 0);
        return NULL;
    }

    const unsigned char* yData = reinterpret_cast<const unsigned char*>(yBytes);
    const unsigned char* uData = reinterpret_cast<const unsigned char*>(uBytes);
    const unsigned char* vData = reinterpret_cast<const unsigned char*>(vBytes);
    
    // Calculate UV dimensions
    int uvWidth = (width + 1) / 2;

    // Create output array
    jintArray result = env->NewIntArray(width * height);
    if (result == NULL) {
        env->ReleaseByteArrayElements(yData_, yBytes, 0);
        env->ReleaseByteArrayElements(uData_, uBytes, 0);
        env->ReleaseByteArrayElements(vData_, vBytes, 0);
        return NULL;
    }

    jint* pixels = env->GetIntArrayElements(result, NULL);
    if (pixels == NULL) {
        env->ReleaseByteArrayElements(yData_, yBytes, 0);
        env->ReleaseByteArrayElements(uData_, uBytes, 0);
        env->ReleaseByteArrayElements(vData_, vBytes, 0);
        return NULL;
    }

    // Multi-threaded processing
    if (numThreads == 1) {
        processRows(0, height, width, height, yData, uData, vData, uvWidth, pixels, 
                   redSource, greenSource, blueSource, flipUV);
    } else {
        std::vector<std::thread> threads;
        int rowsPerThread = height / numThreads;
        
        for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
            int startY = threadIndex * rowsPerThread;
            int endY = (threadIndex == numThreads - 1) ? height : (threadIndex + 1) * rowsPerThread;
            
            threads.emplace_back(processRows, startY, endY, width, height, yData, uData, vData, uvWidth, pixels,
                               redSource, greenSource, blueSource, flipUV);
        }
        
        // Wait for all threads to complete
        for (auto& thread : threads) {
            thread.join();
        }
    }

    // Release arrays
    env->ReleaseIntArrayElements(result, pixels, 0);
    env->ReleaseByteArrayElements(yData_, yBytes, 0);
    env->ReleaseByteArrayElements(uData_, uBytes, 0);
    env->ReleaseByteArrayElements(vData_, vBytes, 0);

    return result;
} 