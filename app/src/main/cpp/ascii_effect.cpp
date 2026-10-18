// Native implementation of AsciiEffect: per character cell, the average brightness picks the
// character and the average color (in the color modes) picks its color; then the character
// template is stamped into the output bitmap for every cell. Renders straight into the bitmap.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry points so the kernels can be unit tested
// on the host (app/src/test/cpp/ascii_test.cpp).

#include <algorithm>
#include <cstdint>
#include <vector>

#include "pixel_format.h"
#include "worker_pool.h"
#include "yuv.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "AsciiEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace ascii {

// Color mode constants matching the Kotlin enum.
enum ColorMode {
    FIXED = 0,
    PRIMARY = 1,
    FULL = 2
};

// Character cell rows per work chunk; each block row covers many image rows.
constexpr int kBlockRowsPerChunk = 1;

// Dominant-component color: each channel is 255 if it is close to the brightest one, else 0.
inline int extractPrimaryColor(int argb) {
    int r = (argb >> 16) & 0xFF;
    int g = (argb >> 8) & 0xFF;
    int b = argb & 0xFF;
    int maxComponent = (r >= g && r >= b) ? r : (g >= r && g >= b) ? g : b;
    int threshold = maxComponent * 0.875;
    int primaryRed = (r >= threshold) ? 255 : 0;
    int primaryGreen = (g >= threshold) ? 255 : 0;
    int primaryBlue = (b >= threshold) ? 255 : 0;
    return (0xFF << 24 | (primaryRed << 16) | (primaryGreen << 8) | primaryBlue);
}

// Computes the character index and ARGB color of every cell in block rows [startRow, endRow).
void computeCellRows(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
                     int width, int height, int numCharCols, int numCharRows, bool isPortrait,
                     int colorMode, int pixelCharsLength, int textColor,
                     int32_t* characterIndices, int32_t* characterColors,
                     int startRow, int endRow) {
    const int uvSize = (width / 2) * (height / 2);
    for (int blockY = startRow; blockY < endRow; ++blockY) {
        for (int blockX = 0; blockX < numCharCols; ++blockX) {
            const int blockIndex = blockY * numCharCols + blockX;

            // Input pixel region for this character cell.
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

            long brightnessTotal = 0;
            long rTotal = 0, gTotal = 0, bTotal = 0;
            int pixelCount = 0;
            const bool wantColor = colorMode == PRIMARY || colorMode == FULL;
            for (int yy = ymin; yy < ymax; ++yy) {
                for (int xx = xmin; xx < xmax; ++xx) {
                    if (xx >= 0 && xx < width && yy >= 0 && yy < height) {
                        const int yIndex = yy * width + xx;
                        const int uvIndex = (yy / 2) * (width / 2) + (xx / 2);
                        const int y = yData[yIndex] & 0xFF;
                        const int u = (uvIndex < uvSize) ? (uData[uvIndex] & 0xFF) : 128;
                        const int v = (uvIndex < uvSize) ? (vData[uvIndex] & 0xFF) : 128;
                        brightnessTotal += y;
                        if (wantColor) {
                            const int argb = static_cast<int>(YuvUtils::yuvToRgbFixed(y, u, v, true));
                            rTotal += (argb >> 16) & 0xFF;
                            gTotal += (argb >> 8) & 0xFF;
                            bTotal += argb & 0xFF;
                        }
                        ++pixelCount;
                    }
                }
            }

            if (pixelCount > 0) {
                const int avgBrightness = static_cast<int>(brightnessTotal / pixelCount);
                // 0 = darkest, pixelCharsLength-1 = brightest.
                const int charIndex = std::min(pixelCharsLength - 1,
                    static_cast<int>((static_cast<double>(avgBrightness) / 255.0) * pixelCharsLength));
                characterIndices[blockIndex] = charIndex;

                int color;
                switch (colorMode) {
                    case PRIMARY: {
                        const int avgR = static_cast<int>(rTotal / pixelCount);
                        const int avgG = static_cast<int>(gTotal / pixelCount);
                        const int avgB = static_cast<int>(bTotal / pixelCount);
                        color = extractPrimaryColor((0xFF << 24) | (avgR << 16) | (avgG << 8) | avgB);
                        break;
                    }
                    case FULL: {
                        const int avgR = static_cast<int>(rTotal / pixelCount);
                        const int avgG = static_cast<int>(gTotal / pixelCount);
                        const int avgB = static_cast<int>(bTotal / pixelCount);
                        color = (0xFF << 24) | (avgR << 16) | (avgG << 8) | avgB;
                        break;
                    }
                    case FIXED:
                    default:
                        color = textColor;
                        break;
                }
                characterColors[blockIndex] = color;
            } else {
                characterIndices[blockIndex] = 0;
                characterColors[blockIndex] = textColor;
            }
        }
    }
}

// Fills `characterIndices` and `characterColors` (ARGB), each numCharCols * numCharRows long.
void computeAsciiData(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
                      int width, int height, int numCharCols, int numCharRows, bool isPortrait,
                      int colorMode, int pixelCharsLength, int textColor,
                      int32_t* characterIndices, int32_t* characterColors, int numThreads) {
    vc::parallelRows(numThreads, numCharRows, kBlockRowsPerChunk, [&](int start, int end) {
        computeCellRows(yData, uData, vData, width, height, numCharCols, numCharRows,
                        isPortrait, colorMode, pixelCharsLength, textColor,
                        characterIndices, characterColors, start, end);
    });
}

// Stamps the character template into the output for the cells of block rows [startRow, endRow).
// Template pixels and `backgroundColor` are ARGB; `cellPixels` and `backgroundPixel` are the
// already converted bitmap pixels that get written. Output rows are `pixelStride` apart.
void renderBlockRows(const uint32_t* templatePixels, int templateWidth, int templateHeight,
                     int charWidth, int charHeight, const int32_t* characterIndices,
                     const uint32_t* cellPixels, int numCharColumns, bool isPortrait,
                     int outputWidth, int outputHeight, uint32_t* outputPixels, int pixelStride,
                     uint32_t backgroundColor, uint32_t backgroundPixel,
                     int startRow, int endRow) {
    const uint8_t bgRed = (backgroundColor >> 16) & 0xFF;
    const uint8_t bgGreen = (backgroundColor >> 8) & 0xFF;
    const uint8_t bgBlue = backgroundColor & 0xFF;
    for (int blockY = startRow; blockY < endRow; blockY++) {
        for (int blockX = 0; blockX < numCharColumns; blockX++) {
            const int cellIndex = blockY * numCharColumns + blockX;
            const int charIndex = characterIndices[cellIndex];
            const uint32_t color = cellPixels[cellIndex];

            const int srcLeft = charIndex * charWidth;
            const int srcRight = srcLeft + charWidth;

            int dstLeft, dstTop, dstWidth, dstHeight;
            if (isPortrait) {
                // Portrait mode: characters are rotated.
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

            for (int dy = 0; dy < dstHeight; dy++) {
                const int outY = dstTop + dy;
                if (outY < 0 || outY >= outputHeight) continue;
                uint32_t* outRow = outputPixels + static_cast<size_t>(outY) * pixelStride;
                for (int dx = 0; dx < dstWidth; dx++) {
                    int srcX, srcY;
                    if (isPortrait) {
                        // Rotate -90 degrees.
                        srcX = srcLeft + (charWidth - 1 - dy);
                        srcY = dx;
                    } else {
                        srcX = srcLeft + dx;
                        srcY = dy;
                    }
                    if (srcX >= srcLeft && srcX < srcRight && srcY >= 0 && srcY < templateHeight) {
                        const uint32_t templatePixel = templatePixels[srcY * templateWidth + srcX];
                        const uint8_t templateRed = (templatePixel >> 16) & 0xFF;
                        const uint8_t templateGreen = (templatePixel >> 8) & 0xFF;
                        const uint8_t templateBlue = templatePixel & 0xFF;
                        // Character color where the template differs from the background color.
                        const uint32_t outputPixel =
                            (templateRed != bgRed || templateGreen != bgGreen || templateBlue != bgBlue)
                                ? color : backgroundPixel;
                        const int outX = dstLeft + dx;
                        if (outX >= 0 && outX < outputWidth) {
                            outRow[outX] = outputPixel;
                        }
                    }
                }
            }
        }
    }
}

// Renders the whole character grid: clears the output to the background, then stamps every
// cell. `characterColors` and `backgroundColor` are ARGB; the output receives bitmap pixels
// (see pixel_format.h), rows `pixelStride` uint32s apart.
void renderCharacterGrid(const uint32_t* templatePixels, int templateWidth, int templateHeight,
                         int charWidth, int charHeight, const int32_t* characterIndices,
                         const int32_t* characterColors, int numCharColumns, int numCharRows,
                         bool isPortrait, int outputWidth, int outputHeight,
                         uint32_t* outputPixels, int pixelStride, uint32_t backgroundColor,
                         int numThreads) {
    const uint32_t backgroundPixel = vc::argbToRgba(backgroundColor);
    std::vector<uint32_t> cellPixels(static_cast<size_t>(numCharColumns) * numCharRows);
    for (size_t i = 0; i < cellPixels.size(); i++) {
        cellPixels[i] = vc::argbToRgba(static_cast<uint32_t>(characterColors[i]));
    }
    vc::parallelRows(numThreads, outputHeight, [&](int start, int end) {
        for (int y = start; y < end; y++) {
            uint32_t* row = outputPixels + static_cast<size_t>(y) * pixelStride;
            std::fill(row, row + outputWidth, backgroundPixel);
        }
    });
    vc::parallelRows(numThreads, numCharRows, kBlockRowsPerChunk, [&](int start, int end) {
        renderBlockRows(templatePixels, templateWidth, templateHeight, charWidth, charHeight,
                        characterIndices, cellPixels.data(), numCharColumns, isPortrait,
                        outputWidth, outputHeight, outputPixels, pixelStride, backgroundColor,
                        backgroundPixel, start, end);
    });
}

}  // namespace ascii

#ifndef VC_NO_JNI
extern "C" JNIEXPORT jintArray JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_AsciiEffect_computeAsciiDataNativeFromPlanes(
    JNIEnv *env,
    jobject /* this */,
    jbyteArray yData_,
    jbyteArray uData_,
    jbyteArray vData_,
    jint width,
    jint height,
    jint numCharCols,
    jint numCharRows,
    jboolean isPortrait,
    jint colorMode,
    jint pixelCharsLength,
    jint textColor,
    jint numThreads) {
    jbyte* yBytes = env->GetByteArrayElements(yData_, nullptr);
    jbyte* uBytes = env->GetByteArrayElements(uData_, nullptr);
    jbyte* vBytes = env->GetByteArrayElements(vData_, nullptr);
    if (!yBytes || !uBytes || !vBytes) {
        LOGE("Failed to get plane byte arrays");
        if (yBytes) env->ReleaseByteArrayElements(yData_, yBytes, JNI_ABORT);
        if (uBytes) env->ReleaseByteArrayElements(uData_, uBytes, JNI_ABORT);
        if (vBytes) env->ReleaseByteArrayElements(vData_, vBytes, JNI_ABORT);
        return nullptr;
    }

    const int numCells = numCharCols * numCharRows;
    // Result array: [characterIndices..., characterColors...]
    jintArray result = env->NewIntArray(numCells * 2);
    if (!result) {
        env->ReleaseByteArrayElements(yData_, yBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(uData_, uBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(vData_, vBytes, JNI_ABORT);
        return nullptr;
    }
    jint* resultData = env->GetIntArrayElements(result, nullptr);
    ascii::computeAsciiData(
            reinterpret_cast<const uint8_t*>(yBytes), reinterpret_cast<const uint8_t*>(uBytes),
            reinterpret_cast<const uint8_t*>(vBytes), width, height, numCharCols, numCharRows,
            isPortrait == JNI_TRUE, colorMode, pixelCharsLength, textColor,
            resultData, resultData + numCells, std::max(1, static_cast<int>(numThreads)));

    env->ReleaseIntArrayElements(result, resultData, 0);
    env->ReleaseByteArrayElements(yData_, yBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(uData_, uBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(vData_, vBytes, JNI_ABORT);
    return result;
}

// Renders the character grid into `bitmap` (RGBA_8888, outputWidth x outputHeight). Returns
// false without touching the bitmap if it can't be used directly.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_AsciiEffect_renderCharacterGridNativeToBitmap(
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
    jobject bitmap,
    jint backgroundColor,
    jint numThreads
) {
    vc::LockedBitmap locked(env, bitmap, outputWidth, outputHeight);
    if (!locked.ok()) {
        return JNI_FALSE;
    }
    jint* templateInts = env->GetIntArrayElements(templatePixels, nullptr);
    jint* charIndices = env->GetIntArrayElements(characterIndices, nullptr);
    jint* charColors = env->GetIntArrayElements(characterColors, nullptr);
    if (!templateInts || !charIndices || !charColors) {
        LOGE("Failed to get array elements for ASCII character grid rendering");
        if (templateInts) env->ReleaseIntArrayElements(templatePixels, templateInts, JNI_ABORT);
        if (charIndices) env->ReleaseIntArrayElements(characterIndices, charIndices, JNI_ABORT);
        if (charColors) env->ReleaseIntArrayElements(characterColors, charColors, JNI_ABORT);
        return JNI_FALSE;
    }
    ascii::renderCharacterGrid(
            reinterpret_cast<const uint32_t*>(templateInts), templateWidth, templateHeight,
            charWidth, charHeight, charIndices, charColors, numCharColumns, numCharRows,
            isPortrait == JNI_TRUE, outputWidth, outputHeight, locked.pixels(), locked.stride(),
            static_cast<uint32_t>(backgroundColor), std::max(1, static_cast<int>(numThreads)));
    env->ReleaseIntArrayElements(templatePixels, templateInts, JNI_ABORT);
    env->ReleaseIntArrayElements(characterIndices, charIndices, JNI_ABORT);
    env->ReleaseIntArrayElements(characterColors, charColors, JNI_ABORT);
    return JNI_TRUE;
}
#endif
