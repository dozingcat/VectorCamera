// Native implementation of MatrixEffect's heavy parts: per-cell brightness averages, optional
// edge detection on the brightness grid, and stamping the character template into the output
// bitmap for every cell. Renders straight into the bitmap.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry points so the kernels can be unit tested
// on the host (app/src/test/cpp/matrix_test.cpp).

#include <algorithm>
#include <cstdint>

#include "character_grid.h"
#include "worker_pool.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "MatrixEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace matrix {

inline int clamp(int value, int min_val, int max_val) {
    return std::max(min_val, std::min(value, max_val));
}

// Average brightness of each cell in block rows [startRow, endRow).
void computeBlockBrightnessRows(const uint8_t* yData, int imageWidth, int imageHeight,
                                int numCharColumns, int numCharRows, bool isPortrait,
                                uint8_t* blockAverages, int startRow, int endRow) {
    for (int blockY = startRow; blockY < endRow; blockY++) {
        for (int blockX = 0; blockX < numCharColumns; blockX++) {
            const int blockIndex = blockY * numCharColumns + blockX;

            const vc::CellInputRect cell = vc::cellInputRect(
                imageWidth, imageHeight, numCharColumns, numCharRows, isPortrait, blockX, blockY);

            uint32_t brightnessTotal = 0;
            int pixelCount = 0;
            for (int yy = cell.ymin; yy < cell.ymax; yy++) {
                if (yy < 0 || yy >= imageHeight) continue;
                const uint8_t* rowPtr = yData + static_cast<size_t>(yy) * imageWidth;
                for (int xx = cell.xmin; xx < cell.xmax; xx++) {
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

void computeBlockBrightness(const uint8_t* yData, int imageWidth, int imageHeight,
                            int numCharColumns, int numCharRows, bool isPortrait,
                            uint8_t* blockAverages, int numThreads) {
    vc::parallelRows(numThreads, numCharRows, vc::kCharacterRowsPerChunk, [&](int start, int end) {
        computeBlockBrightnessRows(yData, imageWidth, imageHeight, numCharColumns, numCharRows,
                                   isPortrait, blockAverages, start, end);
    });
}

// Laplacian edge detection on the brightness grid; border cells become 0.
void applyEdgeDetection(const uint8_t* input, uint8_t* output, int width, int height,
                        int multiplier) {
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            const int index = y * width + x;
            if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                const int center = input[index];
                const int surroundingSum =
                    input[(y - 1) * width + (x - 1)] + input[(y - 1) * width + x] +
                    input[(y - 1) * width + (x + 1)] + input[y * width + (x - 1)] +
                    input[y * width + (x + 1)] + input[(y + 1) * width + (x - 1)] +
                    input[(y + 1) * width + x] + input[(y + 1) * width + (x + 1)];
                const int edge = 8 * center - surroundingSum;
                output[index] = static_cast<uint8_t>(clamp(multiplier * edge, 0, 255));
            } else {
                output[index] = 0;
            }
        }
    }
}

// Renders the character grid into bitmap pixels (see character_grid.h) on a black background.
// `characterColors` are ARGB; characters are drawn where the template is non-black.
void renderCharacterGrid(const uint32_t* templatePixels, int templateWidth, int templateHeight,
                         int charWidth, int charHeight, const int32_t* characterIndices,
                         const int32_t* characterColors, int numCharColumns, int numCharRows,
                         bool isPortrait, bool xFlipped, bool yFlipped, int outputWidth,
                         int outputHeight, uint32_t* outputPixels, int pixelStride,
                         int numThreads) {
    vc::renderCharacterGrid(templatePixels, templateWidth, templateHeight, charWidth, charHeight,
                            characterIndices, characterColors, numCharColumns, numCharRows,
                            isPortrait, xFlipped, yFlipped, outputWidth, outputHeight,
                            outputPixels, pixelStride, /*backgroundColor=*/0xFF000000u, numThreads);
}

}  // namespace matrix

#ifndef VC_NO_JNI
extern "C" {

JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_MatrixEffect_computeBlockBrightnessNative(
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
    jbyte* yBytes = env->GetByteArrayElements(yData, nullptr);
    jbyte* blockBytes = env->GetByteArrayElements(blockAverages, nullptr);
    if (!yBytes || !blockBytes) {
        LOGE("Failed to get array elements");
        if (yBytes) env->ReleaseByteArrayElements(yData, yBytes, JNI_ABORT);
        if (blockBytes) env->ReleaseByteArrayElements(blockAverages, blockBytes, JNI_ABORT);
        return;
    }
    matrix::computeBlockBrightness(reinterpret_cast<const uint8_t*>(yBytes), imageWidth,
                                   imageHeight, numCharColumns, numCharRows,
                                   isPortrait == JNI_TRUE, reinterpret_cast<uint8_t*>(blockBytes),
                                   std::max(1, static_cast<int>(numThreads)));
    env->ReleaseByteArrayElements(yData, yBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(blockAverages, blockBytes, 0);
}

// The grid is a few thousand cells, so this runs single-threaded; numThreads is ignored.
JNIEXPORT void JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_MatrixEffect_applyEdgeDetectionNative(
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
        if (inputBytes) env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
        if (outputBytes) env->ReleaseByteArrayElements(output, outputBytes, JNI_ABORT);
        return;
    }
    matrix::applyEdgeDetection(reinterpret_cast<const uint8_t*>(inputBytes),
                               reinterpret_cast<uint8_t*>(outputBytes), width, height, multiplier);
    env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(output, outputBytes, 0);
}

// Renders the character grid into `bitmap` (RGBA_8888, outputWidth x outputHeight). Returns
// false without touching the bitmap if it can't be used directly.
JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_MatrixEffect_renderCharacterGridNativeToBitmap(
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
    jobject bitmap,
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
        LOGE("Failed to get array elements for character grid rendering");
        if (templateInts) env->ReleaseIntArrayElements(templatePixels, templateInts, JNI_ABORT);
        if (charIndices) env->ReleaseIntArrayElements(characterIndices, charIndices, JNI_ABORT);
        if (charColors) env->ReleaseIntArrayElements(characterColors, charColors, JNI_ABORT);
        return JNI_FALSE;
    }
    matrix::renderCharacterGrid(
            reinterpret_cast<const uint32_t*>(templateInts), templateWidth, templateHeight,
            charWidth, charHeight, charIndices, charColors, numCharColumns, numCharRows,
            isPortrait == JNI_TRUE, xFlipped == JNI_TRUE, yFlipped == JNI_TRUE,
            outputWidth, outputHeight, locked.pixels(), locked.stride(),
            std::max(1, static_cast<int>(numThreads)));
    env->ReleaseIntArrayElements(templatePixels, templateInts, JNI_ABORT);
    env->ReleaseIntArrayElements(characterIndices, charIndices, JNI_ABORT);
    env->ReleaseIntArrayElements(characterColors, charColors, JNI_ABORT);
    return JNI_TRUE;
}

}  // extern "C"
#endif
