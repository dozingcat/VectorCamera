#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cmath>
#include <thread>
#include <vector>

#define LOG_TAG "MatrixEffectNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Force inline critical functions for performance
#define FORCE_INLINE __attribute__((always_inline)) inline

// Clamp function for keeping values in bounds
FORCE_INLINE int clamp(int value, int min_val, int max_val) {
    return std::max(min_val, std::min(value, max_val));
}

/**
 * Compute average brightness for each character cell block.
 * This is the most performance-critical part of the Matrix effect.
 */
void computeBlockBrightness(
    const uint8_t* __restrict__ yData,
    int imageWidth, 
    int imageHeight,
    int numCharColumns,
    int numCharRows,
    bool isPortrait,
    uint8_t* __restrict__ blockAverages,
    int startRow,
    int endRow
) {
    for (int blockY = startRow; blockY < endRow; blockY++) {
        for (int blockX = 0; blockX < numCharColumns; blockX++) {
            const int blockIndex = blockY * numCharColumns + blockX;
            
            // Calculate input pixel region for this character cell
            int xmin, xmax, ymin, ymax;
            
            if (isPortrait) {
                const int inputPixelsPerCol = imageHeight / numCharColumns;
                const int inputPixelsPerRow = imageWidth / numCharRows;
                xmin = blockY * inputPixelsPerRow;
                xmax = xmin + inputPixelsPerRow;
                ymin = (numCharColumns - 1 - blockX) * inputPixelsPerCol;
                ymax = ymin + inputPixelsPerCol;
            } else {
                const int inputPixelsPerCol = imageWidth / numCharColumns;
                const int inputPixelsPerRow = imageHeight / numCharRows;
                xmin = blockX * inputPixelsPerCol;
                xmax = xmin + inputPixelsPerCol;
                ymin = blockY * inputPixelsPerRow;
                ymax = ymin + inputPixelsPerRow;
            }
            
            // Average Y (brightness) values over the block
            uint32_t brightnessTotal = 0;
            int pixelCount = 0;
            
            for (int yy = ymin; yy < ymax; yy++) {
                if (yy < 0 || yy >= imageHeight) continue;
                const uint8_t* rowPtr = yData + yy * imageWidth;
                
                for (int xx = xmin; xx < xmax; xx++) {
                    if (xx >= 0 && xx < imageWidth) {
                        brightnessTotal += rowPtr[xx];
                        pixelCount++;
                    }
                }
            }
            
            blockAverages[blockIndex] = pixelCount > 0 ? 
                static_cast<uint8_t>(brightnessTotal / pixelCount) : 0;
        }
    }
}

/**
 * Apply edge detection to the brightness grid using Laplacian operator.
 */
void applyEdgeDetection(
    const uint8_t* __restrict__ input,
    uint8_t* __restrict__ output,
    int width,
    int height,
    int multiplier,
    int startRow,
    int endRow
) {
    for (int y = startRow; y < endRow; y++) {
        for (int x = 0; x < width; x++) {
            const int index = y * width + x;
            
            if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                const int center = input[index];
                const int surroundingSum =
                    input[(y - 1) * width + (x - 1)] +
                    input[(y - 1) * width + x] +
                    input[(y - 1) * width + (x + 1)] +
                    input[y * width + (x - 1)] +
                    input[y * width + (x + 1)] +
                    input[(y + 1) * width + (x - 1)] +
                    input[(y + 1) * width + x] +
                    input[(y + 1) * width + (x + 1)];
                
                const int edge = 8 * center - surroundingSum;
                output[index] = static_cast<uint8_t>(clamp(multiplier * edge, 0, 255));
            } else {
                output[index] = 0;
            }
        }
    }
}



extern "C" {

/**
 * Compute block brightness averages from YUV data with multi-threading.
 */
JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_MatrixEffectKotlin_computeBlockBrightnessNative(
    JNIEnv *env,
    jobject /* this */,
    jbyteArray yData,
    jint imageWidth,
    jint imageHeight,
    jint numCharColumns,
    jint numCharRows,
    jboolean isPortrait,
    jbyteArray blockAverages,
    jint numThreads
) {
    // Get native array pointers
    jbyte* yBytes = env->GetByteArrayElements(yData, nullptr);
    jbyte* blockBytes = env->GetByteArrayElements(blockAverages, nullptr);
    
    if (!yBytes || !blockBytes) {
        LOGE("Failed to get array elements");
        return;
    }
    
    const uint8_t* yDataPtr = reinterpret_cast<const uint8_t*>(yBytes);
    uint8_t* blockPtr = reinterpret_cast<uint8_t*>(blockBytes);
    
    if (numThreads == 1) {
        computeBlockBrightness(yDataPtr, imageWidth, imageHeight, 
                              numCharColumns, numCharRows, isPortrait,
                              blockPtr, 0, numCharRows);
    } else {
        std::vector<std::thread> threads;
        const int rowsPerThread = numCharRows / numThreads;
        
        for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
            const int startRow = threadIndex * rowsPerThread;
            const int endRow = (threadIndex == numThreads - 1) ? 
                numCharRows : (threadIndex + 1) * rowsPerThread;
            
            threads.emplace_back(computeBlockBrightness, yDataPtr, imageWidth, imageHeight,
                               numCharColumns, numCharRows, isPortrait, blockPtr, startRow, endRow);
        }
        
        // Wait for all threads to complete
        for (auto& thread : threads) {
            thread.join();
        }
    }
    
    // Release arrays
    env->ReleaseByteArrayElements(yData, yBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(blockAverages, blockBytes, 0);
}

/**
 * Apply edge detection to brightness grid with multi-threading.
 */
JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_MatrixEffectKotlin_applyEdgeDetectionNative(
    JNIEnv *env,
    jobject /* this */,
    jbyteArray input,
    jbyteArray output,
    jint width,
    jint height,
    jint multiplier,
    jint numThreads
) {
    jbyte* inputBytes = env->GetByteArrayElements(input, nullptr);
    jbyte* outputBytes = env->GetByteArrayElements(output, nullptr);
    
    if (!inputBytes || !outputBytes) {
        LOGE("Failed to get array elements for edge detection");
        return;
    }
    
    const uint8_t* inputPtr = reinterpret_cast<const uint8_t*>(inputBytes);
    uint8_t* outputPtr = reinterpret_cast<uint8_t*>(outputBytes);
    
    if (numThreads == 1) {
        applyEdgeDetection(inputPtr, outputPtr, width, height, multiplier, 0, height);
    } else {
        std::vector<std::thread> threads;
        const int rowsPerThread = height / numThreads;
        
        for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
            const int startRow = threadIndex * rowsPerThread;
            const int endRow = (threadIndex == numThreads - 1) ? 
                height : (threadIndex + 1) * rowsPerThread;
            
            threads.emplace_back(applyEdgeDetection, inputPtr, outputPtr, width, height, 
                               multiplier, startRow, endRow);
        }
        
        for (auto& thread : threads) {
            thread.join();
        }
    }
    
    env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(output, outputBytes, 0);
}



/**
 * Check if native library is available.
 */
JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_MatrixEffectKotlin_isNativeAvailable(
    JNIEnv *env,
    jobject /* this */
) {
    return JNI_TRUE;
}

} // extern "C" 