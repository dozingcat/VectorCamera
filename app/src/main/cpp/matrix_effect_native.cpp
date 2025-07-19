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

/**
 * Render the entire character grid to a pixel buffer in parallel.
 * This replaces thousands of individual character rendering calls with one bulk operation.
 */
void renderCharacterGrid(
    const uint32_t* __restrict__ templatePixels,
    int templateWidth,
    int templateHeight,
    int charWidth,
    int charHeight,
    const int* __restrict__ characterIndices,
    const uint32_t* __restrict__ characterColors,
    int numCharColumns,
    int numCharRows,
    bool isPortrait,
    bool xFlipped,
    bool yFlipped,
    int outputWidth,
    int outputHeight,
    uint32_t* __restrict__ outputPixels,
    int startRow,
    int endRow
) {
    const uint32_t BLACK = 0xFF000000;
    
    for (int blockY = startRow; blockY < endRow; blockY++) {
        for (int blockX = 0; blockX < numCharColumns; blockX++) {
            const int cellIndex = blockY * numCharColumns + blockX;
            const int charIndex = characterIndices[cellIndex];
            const uint32_t color = characterColors[cellIndex];
            
            // Calculate source rectangle in character template
            const int srcLeft = charIndex * charWidth;
            const int srcRight = srcLeft + charWidth;
            
            // Calculate destination rectangle in output
            int dstLeft, dstTop, dstWidth, dstHeight;
            
            if (isPortrait) {
                // Portrait mode: characters are rotated
                dstLeft = blockY * charHeight;
                dstTop = (numCharColumns - 1 - blockX) * charWidth;
                dstWidth = charHeight;
                dstHeight = charWidth;
            } else {
                dstLeft = blockX * charWidth;
                dstTop = blockY * charHeight;
                dstWidth = charWidth;
                dstHeight = charHeight;
            }
            
            // Render character pixels
            for (int dy = 0; dy < dstHeight; dy++) {
                for (int dx = 0; dx < dstWidth; dx++) {
                    int srcX, srcY;
                    
                    if (isPortrait) {
                        // Portrait transformation: rotate -90 degrees
                        srcX = srcLeft + (yFlipped ? dy : charWidth - 1 - dy);
                        srcY = xFlipped ? charHeight - 1 - dx : dx;
                    } else {
                        // Landscape transformation: apply flipping
                        srcX = srcLeft + (xFlipped ? charWidth - 1 - dx : dx);
                        srcY = yFlipped ? charHeight - 1 - dy : dy;
                    }
                    
                    // Bounds check for template
                    if (srcX >= srcLeft && srcX < srcRight && srcY >= 0 && srcY < templateHeight) {
                        const uint32_t templatePixel = templatePixels[srcY * templateWidth + srcX];
                        
                        // Check if template pixel is non-black
                        const uint8_t red = (templatePixel >> 16) & 0xFF;
                        const uint8_t green = (templatePixel >> 8) & 0xFF;
                        const uint8_t blue = templatePixel & 0xFF;
                        
                        // Determine output pixel color
                        const uint32_t outputColor = (red > 0 || green > 0 || blue > 0) ? color : BLACK;
                        
                        // Write to output buffer
                        const int outX = dstLeft + dx;
                        const int outY = dstTop + dy;
                        if (outX >= 0 && outX < outputWidth && outY >= 0 && outY < outputHeight) {
                            outputPixels[outY * outputWidth + outX] = outputColor;
                        }
                    }
                }
            }
        }
    }
}

/**
 * Render all characters to output buffer with multi-threading.
 */
JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_MatrixEffectKotlin_renderCharacterGridNative(
    JNIEnv *env,
    jobject /* this */,
    jintArray templatePixels,
    jint templateWidth,
    jint templateHeight,
    jint charWidth,
    jint charHeight,
    jintArray characterIndices,
    jintArray characterColors,
    jint numCharColumns,
    jint numCharRows,
    jboolean isPortrait,
    jboolean xFlipped,
    jboolean yFlipped,
    jint outputWidth,
    jint outputHeight,
    jintArray outputPixels,
    jint numThreads
) {
    // Get native array pointers
    jint* templateInts = env->GetIntArrayElements(templatePixels, nullptr);
    jint* charIndices = env->GetIntArrayElements(characterIndices, nullptr);
    jint* charColors = env->GetIntArrayElements(characterColors, nullptr);
    jint* outputInts = env->GetIntArrayElements(outputPixels, nullptr);
    
    if (!templateInts || !charIndices || !charColors || !outputInts) {
        LOGE("Failed to get array elements for character grid rendering");
        return;
    }
    
    const uint32_t* templatePtr = reinterpret_cast<const uint32_t*>(templateInts);
    const int* indicesPtr = reinterpret_cast<const int*>(charIndices);
    const uint32_t* colorsPtr = reinterpret_cast<const uint32_t*>(charColors);
    uint32_t* outputPtr = reinterpret_cast<uint32_t*>(outputInts);
    
    // Clear output buffer to black
    const int totalPixels = outputWidth * outputHeight;
    std::fill(outputPtr, outputPtr + totalPixels, 0xFF000000);
    
    if (numThreads == 1) {
        renderCharacterGrid(templatePtr, templateWidth, templateHeight, charWidth, charHeight,
                           indicesPtr, colorsPtr, numCharColumns, numCharRows,
                           isPortrait, xFlipped, yFlipped, outputWidth, outputHeight,
                           outputPtr, 0, numCharRows);
    } else {
        std::vector<std::thread> threads;
        const int rowsPerThread = numCharRows / numThreads;
        
        for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
            const int startRow = threadIndex * rowsPerThread;
            const int endRow = (threadIndex == numThreads - 1) ? 
                numCharRows : (threadIndex + 1) * rowsPerThread;
            
            threads.emplace_back(renderCharacterGrid, templatePtr, templateWidth, templateHeight,
                               charWidth, charHeight, indicesPtr, colorsPtr, numCharColumns, numCharRows,
                               isPortrait, xFlipped, yFlipped, outputWidth, outputHeight,
                               outputPtr, startRow, endRow);
        }
        
        // Wait for all threads to complete
        for (auto& thread : threads) {
            thread.join();
        }
    }
    
    // Release arrays
    env->ReleaseIntArrayElements(templatePixels, templateInts, JNI_ABORT);
    env->ReleaseIntArrayElements(characterIndices, charIndices, JNI_ABORT);
    env->ReleaseIntArrayElements(characterColors, charColors, JNI_ABORT);
    env->ReleaseIntArrayElements(outputPixels, outputInts, 0);
}

} // extern "C" 