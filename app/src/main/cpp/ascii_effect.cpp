// Native implementation of AsciiEffect: per character cell, the average brightness picks the
// character and the average color (in the color modes) picks its color; then the character
// template is stamped into the output bitmap for every cell. Renders straight into the bitmap.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry points so the kernels can be unit tested
// on the host (app/src/test/cpp/ascii_test.cpp).

#include <algorithm>
#include <cstdint>

#include "character_grid.h"
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

            const vc::CellInputRect cell =
                vc::cellInputRect(width, height, numCharCols, numCharRows, isPortrait, blockX, blockY);

            long brightnessTotal = 0;
            long rTotal = 0, gTotal = 0, bTotal = 0;
            int pixelCount = 0;
            const bool wantColor = colorMode == PRIMARY || colorMode == FULL;
            for (int yy = cell.ymin; yy < cell.ymax; ++yy) {
                for (int xx = cell.xmin; xx < cell.xmax; ++xx) {
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
    vc::parallelRows(numThreads, numCharRows, vc::kCharacterRowsPerChunk, [&](int start, int end) {
        computeCellRows(yData, uData, vData, width, height, numCharCols, numCharRows,
                        isPortrait, colorMode, pixelCharsLength, textColor,
                        characterIndices, characterColors, start, end);
    });
}

// Renders the character grid into bitmap pixels (see character_grid.h). `characterColors` and
// `backgroundColor` are ARGB; characters are drawn where the template differs from the
// background color.
void renderCharacterGrid(const uint32_t* templatePixels, int templateWidth, int templateHeight,
                         int charWidth, int charHeight, const int32_t* characterIndices,
                         const int32_t* characterColors, int numCharColumns, int numCharRows,
                         bool isPortrait, int outputWidth, int outputHeight,
                         uint32_t* outputPixels, int pixelStride, uint32_t backgroundColor,
                         int numThreads) {
    vc::renderCharacterGrid(templatePixels, templateWidth, templateHeight, charWidth, charHeight,
                            characterIndices, characterColors, numCharColumns, numCharRows,
                            isPortrait, /*xFlipped=*/false, /*yFlipped=*/false, outputWidth,
                            outputHeight, outputPixels, pixelStride, backgroundColor, numThreads);
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
