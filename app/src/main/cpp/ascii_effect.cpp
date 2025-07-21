#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <thread>
#include <vector>

#define LOG_TAG "AsciiEffectNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Force inline critical functions for performance
#define FORCE_INLINE __attribute__((always_inline)) inline

// Color mode constants matching Kotlin enum
enum ColorMode {
    FIXED = 0,
    PRIMARY = 1,
    FULL = 2
};

/**
 * Fast YUV to RGB conversion optimized for brightness extraction.
 * Only computes brightness component for efficiency.
 */
inline int yuvToBrightness(int y, int u, int v) {
    // Fast brightness calculation using Y component with slight UV influence
    int r = y + ((359 * (v - 128)) >> 8);
    int g = y - ((88 * (u - 128) + 183 * (v - 128)) >> 8);
    int b = y + ((454 * (u - 128)) >> 8);
    
    // Clamp values
    r = std::max(0, std::min(255, r));
    g = std::max(0, std::min(255, g));
    b = std::max(0, std::min(255, b));
    
    // Return brightness (luminance)
    return (r * 299 + g * 587 + b * 114) / 1000;
}

/**
 * Fast YUV to ARGB conversion for color extraction.
 */
inline int yuvToArgb(int y, int u, int v) {
    int r = y + ((359 * (v - 128)) >> 8);
    int g = y - ((88 * (u - 128) + 183 * (v - 128)) >> 8);
    int b = y + ((454 * (u - 128)) >> 8);
    
    // Clamp values
    r = std::max(0, std::min(255, r));
    g = std::max(0, std::min(255, g));
    b = std::max(0, std::min(255, b));
    
    return (0xFF << 24) | (r << 16) | (g << 8) | b;
}

/**
 * Extract primary color from RGB using simple dominant component method.
 */
inline int extractPrimaryColor(int argb) {
    int r = (argb >> 16) & 0xFF;
    int g = (argb >> 8) & 0xFF;
    int b = argb & 0xFF;
    
    // Find dominant component and enhance it
    if (r >= g && r >= b) {
        return (0xFF << 24) | (std::min(255, r * 3 / 2) << 16) | (g / 2 << 8) | (b / 2);
    } else if (g >= r && g >= b) {
        return (0xFF << 24) | (r / 2 << 16) | (std::min(255, g * 3 / 2) << 8) | (b / 2);
    } else {
        return (0xFF << 24) | (r / 2 << 16) | (g / 2 << 8) | std::min(255, b * 3 / 2);
    }
}

/**
 * Render ASCII character grid to output buffer in parallel.
 * This replaces thousands of individual character Canvas operations with one bulk operation.
 */
void renderAsciiCharacterGrid(
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
    int outputWidth,
    int outputHeight,
    uint32_t* __restrict__ outputPixels,
    uint32_t backgroundColor,
    int startRow,
    int endRow
) {
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
                        srcX = srcLeft + (charWidth - 1 - dy);
                        srcY = dx;
                    } else {
                        // Landscape: no transformation needed
                        srcX = srcLeft + dx;
                        srcY = dy;
                    }
                    
                    // Bounds check for template
                    if (srcX >= srcLeft && srcX < srcRight && srcY >= 0 && srcY < templateHeight) {
                        const uint32_t templatePixel = templatePixels[srcY * templateWidth + srcX];
                        
                        // Extract template pixel components
                        const uint8_t templateRed = (templatePixel >> 16) & 0xFF;
                        const uint8_t templateGreen = (templatePixel >> 8) & 0xFF;
                        const uint8_t templateBlue = templatePixel & 0xFF;
                        
                        // Extract background color components for comparison
                        const uint8_t bgRed = (backgroundColor >> 16) & 0xFF;
                        const uint8_t bgGreen = (backgroundColor >> 8) & 0xFF;
                        const uint8_t bgBlue = backgroundColor & 0xFF;
                        
                        // Use character color if template pixel differs from background color
                        // This matches the Kotlin logic exactly
                        const uint32_t outputColor = (templateRed != bgRed || 
                                                    templateGreen != bgGreen || 
                                                    templateBlue != bgBlue) ? 
                            color : backgroundColor;
                        
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

extern "C" JNIEXPORT jintArray JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_AsciiEffect_computeAsciiDataNative(
    JNIEnv *env,
    jobject /* this */,
    jbyteArray yuvBytes,
    jint width,
    jint height,
    jint numCharCols,
    jint numCharRows,
    jboolean isPortrait,
    jint colorMode,
    jint pixelCharsLength,
    jint textColor) {

    // Get YUV data
    jbyte* yuv = env->GetByteArrayElements(yuvBytes, nullptr);
    if (!yuv) {
        LOGI("Failed to get YUV byte array");
        return nullptr;
    }

    const int ySize = width * height;
    const int uvSize = (width / 2) * (height / 2);
    const auto* yData = reinterpret_cast<const uint8_t*>(yuv);
    const auto* uData = reinterpret_cast<const uint8_t*>(yuv + ySize);
    const auto* vData = reinterpret_cast<const uint8_t*>(yuv + ySize + uvSize);

    const int numCells = numCharCols * numCharRows;
    
    // Result array: [characterIndices..., characterColors...]
    jintArray result = env->NewIntArray(numCells * 2);
    if (!result) {
        env->ReleaseByteArrayElements(yuvBytes, yuv, JNI_ABORT);
        return nullptr;
    }
    
    jint* resultData = env->GetIntArrayElements(result, nullptr);
    jint* characterIndices = resultData;
    jint* characterColors = resultData + numCells;

    // Process each character cell
    for (int blockY = 0; blockY < numCharRows; ++blockY) {
        for (int blockX = 0; blockX < numCharCols; ++blockX) {
            const int blockIndex = blockY * numCharCols + blockX;
            
            // Calculate input pixel region for this character cell
            int xmin, xmax, ymin, ymax;
            
            if (isPortrait) {
                const int inputPixelsPerCol = height / numCharCols;
                const int inputPixelsPerRow = width / numCharRows;
                xmin = blockY * inputPixelsPerRow;
                xmax = xmin + inputPixelsPerRow;
                ymin = (numCharCols - 1 - blockX) * inputPixelsPerCol;
                ymax = ymin + inputPixelsPerCol;
            } else {
                const int inputPixelsPerCol = width / numCharCols;
                const int inputPixelsPerRow = height / numCharRows;
                xmin = blockX * inputPixelsPerCol;
                xmax = xmin + inputPixelsPerCol;
                ymin = blockY * inputPixelsPerRow;
                ymax = ymin + inputPixelsPerRow;
            }
            
            // Compute brightness and color data for this cell
            long brightnessTotal = 0;
            long rTotal = 0, gTotal = 0, bTotal = 0;
            int pixelCount = 0;
            
            for (int yy = ymin; yy < ymax; ++yy) {
                for (int xx = xmin; xx < xmax; ++xx) {
                    if (xx >= 0 && xx < width && yy >= 0 && yy < height) {
                        const int yIndex = yy * width + xx;
                        const int uvIndex = (yy / 2) * (width / 2) + (xx / 2);
                        
                        const int y = yData[yIndex] & 0xFF;
                        const int u = (uvIndex < uvSize) ? (uData[uvIndex] & 0xFF) : 128;
                        const int v = (uvIndex < uvSize) ? (vData[uvIndex] & 0xFF) : 128;
                        
                        // Accumulate brightness
                        brightnessTotal += y;
                        
                        // For color modes, also accumulate RGB values
                        if (colorMode == PRIMARY || colorMode == FULL) {
                            const int argb = yuvToArgb(y, u, v);
                            rTotal += (argb >> 16) & 0xFF;
                            gTotal += (argb >> 8) & 0xFF;
                            bTotal += argb & 0xFF;
                        }
                        
                        ++pixelCount;
                    }
                }
            }
            
            // Compute character index from average brightness
            if (pixelCount > 0) {
                const int avgBrightness = static_cast<int>(brightnessTotal / pixelCount);
                
                // Map brightness to character index (0 = darkest, pixelCharsLength-1 = brightest)
                const int charIndex = std::min(pixelCharsLength - 1, 
                    static_cast<int>((static_cast<double>(avgBrightness) / 255.0) * pixelCharsLength));
                characterIndices[blockIndex] = charIndex;
                
                // Compute color based on color mode
                int color;
                switch (colorMode) {
                    case FIXED:
                        color = textColor;
                        break;
                    case PRIMARY: {
                        const int avgR = static_cast<int>(rTotal / pixelCount);
                        const int avgG = static_cast<int>(gTotal / pixelCount);
                        const int avgB = static_cast<int>(bTotal / pixelCount);
                        const int avgColor = (0xFF << 24) | (avgR << 16) | (avgG << 8) | avgB;
                        color = extractPrimaryColor(avgColor);
                        break;
                    }
                    case FULL: {
                        const int avgR = static_cast<int>(rTotal / pixelCount);
                        const int avgG = static_cast<int>(gTotal / pixelCount);
                        const int avgB = static_cast<int>(bTotal / pixelCount);
                        color = (0xFF << 24) | (avgR << 16) | (avgG << 8) | avgB;
                        break;
                    }
                    default:
                        color = textColor;
                        break;
                }
                characterColors[blockIndex] = color;
            } else {
                // No pixels in cell
                characterIndices[blockIndex] = 0;
                characterColors[blockIndex] = textColor;
            }
        }
    }
    
    env->ReleaseIntArrayElements(result, resultData, 0);
    env->ReleaseByteArrayElements(yuvBytes, yuv, JNI_ABORT);
    
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_AsciiEffect_computeBlockBrightnessNative(
    JNIEnv *env,
    jobject /* this */,
    jbyteArray yuvBytes,
    jint width,
    jint height,
    jint numCharCols,
    jint numCharRows,
    jboolean isPortrait) {

    // Get YUV data
    jbyte* yuv = env->GetByteArrayElements(yuvBytes, nullptr);
    if (!yuv) {
        return nullptr;
    }

    const auto* yData = reinterpret_cast<const uint8_t*>(yuv);
    const int numCells = numCharCols * numCharRows;
    
    jbyteArray result = env->NewByteArray(numCells);
    if (!result) {
        env->ReleaseByteArrayElements(yuvBytes, yuv, JNI_ABORT);
        return nullptr;
    }
    
    jbyte* resultData = env->GetByteArrayElements(result, nullptr);

    // Process each character cell for brightness only
    for (int blockY = 0; blockY < numCharRows; ++blockY) {
        for (int blockX = 0; blockX < numCharCols; ++blockX) {
            const int blockIndex = blockY * numCharCols + blockX;
            
            // Calculate input pixel region
            int xmin, xmax, ymin, ymax;
            
            if (isPortrait) {
                const int inputPixelsPerCol = height / numCharCols;
                const int inputPixelsPerRow = width / numCharRows;
                xmin = blockY * inputPixelsPerRow;
                xmax = xmin + inputPixelsPerRow;
                ymin = (numCharCols - 1 - blockX) * inputPixelsPerCol;
                ymax = ymin + inputPixelsPerCol;
            } else {
                const int inputPixelsPerCol = width / numCharCols;
                const int inputPixelsPerRow = height / numCharRows;
                xmin = blockX * inputPixelsPerCol;
                xmax = xmin + inputPixelsPerCol;
                ymin = blockY * inputPixelsPerRow;
                ymax = ymin + inputPixelsPerRow;
            }
            
            // Average brightness over the block
            long brightnessTotal = 0;
            int pixelCount = 0;
            
            for (int yy = ymin; yy < ymax; ++yy) {
                for (int xx = xmin; xx < xmax; ++xx) {
                    if (xx >= 0 && xx < width && yy >= 0 && yy < height) {
                        brightnessTotal += yData[yy * width + xx] & 0xFF;
                        ++pixelCount;
                    }
                }
            }
            
            resultData[blockIndex] = pixelCount > 0 
                ? static_cast<jbyte>(brightnessTotal / pixelCount) 
                : 0;
        }
    }
    
    env->ReleaseByteArrayElements(result, resultData, 0);
    env->ReleaseByteArrayElements(yuvBytes, yuv, JNI_ABORT);
    
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_AsciiEffect_renderCharacterGridNative(
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
    jint outputWidth,
    jint outputHeight,
    jintArray outputPixels,
    jint backgroundColor,
    jint numThreads
) {
    // Get native array pointers
    jint* templateInts = env->GetIntArrayElements(templatePixels, nullptr);
    jint* charIndices = env->GetIntArrayElements(characterIndices, nullptr);
    jint* charColors = env->GetIntArrayElements(characterColors, nullptr);
    jint* outputInts = env->GetIntArrayElements(outputPixels, nullptr);
    
    if (!templateInts || !charIndices || !charColors || !outputInts) {
        LOGE("Failed to get array elements for ASCII character grid rendering");
        return;
    }
    
    const uint32_t* templatePtr = reinterpret_cast<const uint32_t*>(templateInts);
    const int* indicesPtr = reinterpret_cast<const int*>(charIndices);
    const uint32_t* colorsPtr = reinterpret_cast<const uint32_t*>(charColors);
    uint32_t* outputPtr = reinterpret_cast<uint32_t*>(outputInts);
    const uint32_t bgColor = static_cast<uint32_t>(backgroundColor);
    
    // Clear output buffer to background color
    const int totalPixels = outputWidth * outputHeight;
    std::fill(outputPtr, outputPtr + totalPixels, bgColor);
    
    if (numThreads == 1) {
        renderAsciiCharacterGrid(templatePtr, templateWidth, templateHeight, charWidth, charHeight,
                                indicesPtr, colorsPtr, numCharColumns, numCharRows,
                                isPortrait, outputWidth, outputHeight, outputPtr, bgColor, 0, numCharRows);
    } else {
        std::vector<std::thread> threads;
        const int rowsPerThread = numCharRows / numThreads;
        
        for (int threadIndex = 0; threadIndex < numThreads; threadIndex++) {
            const int startRow = threadIndex * rowsPerThread;
            const int endRow = (threadIndex == numThreads - 1) ? 
                numCharRows : (threadIndex + 1) * rowsPerThread;
            
            threads.emplace_back(renderAsciiCharacterGrid, templatePtr, templateWidth, templateHeight,
                               charWidth, charHeight, indicesPtr, colorsPtr, numCharColumns, numCharRows,
                               isPortrait, outputWidth, outputHeight, outputPtr, bgColor, startRow, endRow);
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