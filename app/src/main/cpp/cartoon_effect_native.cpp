#include <jni.h>
#include <android/log.h>
#include <thread>
#include <vector>
#include <algorithm>
#include <cmath>
#include "yuv.h"

#define LOG_TAG "CartoonEffectNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Create color quantization LUT that reduces each color component to 2 bits (4 levels)
// Possible RGB values are (0, 85, 170, 255)
inline void createColorLUT(int* lut) {
    for (int index = 0; index < 256; index++) {
        int mapval = static_cast<int>(round(index / 85.0)) * 85;
        lut[index] = mapval;
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
    const int* colorLUT
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

            // Convert YUV to RGB
            int rgb = YuvUtils::yuvToRgb(yy, u, v, false);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            // Apply color quantization using LUT
            int quantizedR = colorLUT[r];
            int quantizedG = colorLUT[g];
            int quantizedB = colorLUT[b];

            // Create final ARGB pixel
            pixels[pixelIndex] = (255 << 24) | (quantizedR << 16) | (quantizedG << 8) | quantizedB;
        }
    }
}

// Apply horizontal blur using sliding window for efficiency
void applyHorizontalBlur(const int* input, int* output, int width, int height, int radius) {
    const int kernelSize = radius * 2 + 1;
    
    for (int y = 0; y < height; y++) {
        int totalR = 0, totalG = 0, totalB = 0;
        
        // Initialize sliding window for first pixel
        for (int kx = -radius; kx <= radius; kx++) {
            int sampleX = std::max(0, std::min(width - 1, kx));
            int sampleIndex = y * width + sampleX;
            int samplePixel = input[sampleIndex];
            
            totalR += (samplePixel >> 16) & 0xFF;
            totalG += (samplePixel >> 8) & 0xFF;
            totalB += samplePixel & 0xFF;
        }
        
        // Apply blur to each pixel in the row using sliding window
        for (int x = 0; x < width; x++) {
            int avgR = totalR / kernelSize;
            int avgG = totalG / kernelSize;
            int avgB = totalB / kernelSize;
            
            int pixelIndex = y * width + x;
            output[pixelIndex] = (255 << 24) | (avgR << 16) | (avgG << 8) | avgB;
            
            // Update sliding window for next pixel
            if (x < width - 1) {
                // Remove leftmost pixel from window
                int leftX = std::max(0, std::min(width - 1, x - radius));
                int leftIndex = y * width + leftX;
                int leftPixel = input[leftIndex];
                totalR -= (leftPixel >> 16) & 0xFF;
                totalG -= (leftPixel >> 8) & 0xFF;
                totalB -= leftPixel & 0xFF;
                
                // Add rightmost pixel to window
                int rightX = std::max(0, std::min(width - 1, x + radius + 1));
                int rightIndex = y * width + rightX;
                int rightPixel = input[rightIndex];
                totalR += (rightPixel >> 16) & 0xFF;
                totalG += (rightPixel >> 8) & 0xFF;
                totalB += rightPixel & 0xFF;
            }
        }
    }
}

// Apply vertical blur using sliding window for efficiency
void applyVerticalBlur(const int* input, int* output, int width, int height, int radius) {
    const int kernelSize = radius * 2 + 1;
    
    for (int x = 0; x < width; x++) {
        int totalR = 0, totalG = 0, totalB = 0;
        
        // Initialize sliding window for first pixel
        for (int ky = -radius; ky <= radius; ky++) {
            int sampleY = std::max(0, std::min(height - 1, ky));
            int sampleIndex = sampleY * width + x;
            int samplePixel = input[sampleIndex];
            
            totalR += (samplePixel >> 16) & 0xFF;
            totalG += (samplePixel >> 8) & 0xFF;
            totalB += samplePixel & 0xFF;
        }
        
        // Apply blur to each pixel in the column using sliding window
        for (int y = 0; y < height; y++) {
            int avgR = totalR / kernelSize;
            int avgG = totalG / kernelSize;
            int avgB = totalB / kernelSize;
            
            int pixelIndex = y * width + x;
            output[pixelIndex] = (255 << 24) | (avgR << 16) | (avgG << 8) | avgB;
            
            // Update sliding window for next pixel
            if (y < height - 1) {
                // Remove topmost pixel from window
                int topY = std::max(0, std::min(height - 1, y - radius));
                int topIndex = topY * width + x;
                int topPixel = input[topIndex];
                totalR -= (topPixel >> 16) & 0xFF;
                totalG -= (topPixel >> 8) & 0xFF;
                totalB -= topPixel & 0xFF;
                
                // Add bottommost pixel to window
                int bottomY = std::max(0, std::min(height - 1, y + radius + 1));
                int bottomIndex = bottomY * width + x;
                int bottomPixel = input[bottomIndex];
                totalR += (bottomPixel >> 16) & 0xFF;
                totalG += (bottomPixel >> 8) & 0xFF;
                totalB += bottomPixel & 0xFF;
            }
        }
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_CartoonEffect_00024Companion_processImageNativeFromYuvBytes(
    JNIEnv* env, 
    jobject /* this */, 
    jbyteArray yuvBytes_, 
    jint width, 
    jint height,
    jint blurRadius,
    jint numThreads
) {
    // Get YUV data
    jbyte* yuvBytes = env->GetByteArrayElements(yuvBytes_, NULL);
    if (yuvBytes == NULL) {
        return NULL;
    }

    const unsigned char* yuvData = reinterpret_cast<const unsigned char*>(yuvBytes);
    
    // Calculate YUV plane sizes and offsets
    int ySize = width * height;
    int uvWidth = (width + 1) / 2;
    int uvHeight = (height + 1) / 2;
    int uvSize = uvWidth * uvHeight;

    const unsigned char* yData = yuvData;
    const unsigned char* uData = yuvData + ySize;
    const unsigned char* vData = yuvData + ySize + uvSize;

    // Create color quantization LUT
    int colorLUT[256];
    createColorLUT(colorLUT);

    // Create output arrays
    jintArray result = env->NewIntArray(width * height);
    if (result == NULL) {
        env->ReleaseByteArrayElements(yuvBytes_, yuvBytes, 0);
        return NULL;
    }

    jint* pixels = env->GetIntArrayElements(result, NULL);
    if (pixels == NULL) {
        env->ReleaseByteArrayElements(yuvBytes_, yuvBytes, 0);
        return NULL;
    }

    // Process image with color quantization using multi-threading
    if (numThreads == 1) {
        processRows(0, height, width, height, yData, uData, vData, uvWidth, pixels, colorLUT);
    } else {
        std::vector<std::thread> threads;
        int rowsPerThread = height / numThreads;
        
        for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
            int startY = threadIndex * rowsPerThread;
            int endY = (threadIndex == numThreads - 1) ? height : (threadIndex + 1) * rowsPerThread;
            
            threads.emplace_back(processRows, startY, endY, width, height, yData, uData, vData, uvWidth, pixels, colorLUT);
        }
        
        // Wait for all threads to complete
        for (auto& thread : threads) {
            thread.join();
        }
    }

    // Apply blur if radius > 0
    if (blurRadius > 0) {
        // Allocate temporary array for horizontal blur
        int* tempPixels = new int[width * height];
        
        // Apply horizontal blur
        applyHorizontalBlur(pixels, tempPixels, width, height, blurRadius);
        
        // Apply vertical blur (back to original array)
        applyVerticalBlur(tempPixels, pixels, width, height, blurRadius);
        
        delete[] tempPixels;
    }

    // Release arrays
    env->ReleaseIntArrayElements(result, pixels, 0);
    env->ReleaseByteArrayElements(yuvBytes_, yuvBytes, 0);

    return result;
} 