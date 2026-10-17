// Native implementation of MatrixEffect's heavy parts: per-cell brightness averages, optional
// edge detection on the brightness grid, and stamping the character template into the output
// bitmap for every cell. Renders straight into the bitmap.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry points so the kernels can be unit tested
// on the host (app/src/test/cpp/matrix_test.cpp).

#include <algorithm>
#include <cstdint>
#include <vector>

#include "pixel_format.h"
#include "worker_pool.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "MatrixEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace matrix {

// Character cell rows per work chunk; each block row covers many image rows.
constexpr int kBlockRowsPerChunk = 1;

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

            uint32_t brightnessTotal = 0;
            int pixelCount = 0;
            for (int yy = ymin; yy < ymax; yy++) {
                if (yy < 0 || yy >= imageHeight) continue;
                const uint8_t* rowPtr = yData + static_cast<size_t>(yy) * imageWidth;
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

void computeBlockBrightness(const uint8_t* yData, int imageWidth, int imageHeight,
                            int numCharColumns, int numCharRows, bool isPortrait,
                            uint8_t* blockAverages, int numThreads) {
    vc::parallelRows(numThreads, numCharRows, kBlockRowsPerChunk, [&](int start, int end) {
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

// Stamps the character template into the output for the cells of block rows [startRow, endRow).
// Template pixels are ARGB; `cellPixels` holds the already converted bitmap pixels per cell.
// Output rows are `pixelStride` uint32s apart.
void renderBlockRows(const uint32_t* templatePixels, int templateWidth, int templateHeight,
                     int charWidth, int charHeight, const int32_t* characterIndices,
                     const uint32_t* cellPixels, int numCharColumns, bool isPortrait,
                     bool xFlipped, bool yFlipped, int outputWidth, int outputHeight,
                     uint32_t* outputPixels, int pixelStride, int startRow, int endRow) {
    const uint32_t BLACK = vc::argbToRgba(0xFF000000u);
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
                        // Rotate -90 degrees, with flips.
                        srcX = srcLeft + (yFlipped ? dy : charWidth - 1 - dy);
                        srcY = xFlipped ? charHeight - 1 - dx : dx;
                    } else {
                        srcX = srcLeft + (xFlipped ? charWidth - 1 - dx : dx);
                        srcY = yFlipped ? charHeight - 1 - dy : dy;
                    }
                    if (srcX >= srcLeft && srcX < srcRight && srcY >= 0 && srcY < templateHeight) {
                        const uint32_t templatePixel = templatePixels[srcY * templateWidth + srcX];
                        const uint8_t red = (templatePixel >> 16) & 0xFF;
                        const uint8_t green = (templatePixel >> 8) & 0xFF;
                        const uint8_t blue = templatePixel & 0xFF;
                        // Character color where the template is non-black.
                        const uint32_t outputPixel = (red > 0 || green > 0 || blue > 0) ? color : BLACK;
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

// Renders the whole character grid: clears the output to black, then stamps every cell.
// `characterColors` are ARGB; the output receives bitmap pixels (see pixel_format.h), rows
// `pixelStride` uint32s apart.
void renderCharacterGrid(const uint32_t* templatePixels, int templateWidth, int templateHeight,
                         int charWidth, int charHeight, const int32_t* characterIndices,
                         const int32_t* characterColors, int numCharColumns, int numCharRows,
                         bool isPortrait, bool xFlipped, bool yFlipped, int outputWidth,
                         int outputHeight, uint32_t* outputPixels, int pixelStride,
                         int numThreads) {
    const uint32_t black = vc::argbToRgba(0xFF000000u);
    std::vector<uint32_t> cellPixels(static_cast<size_t>(numCharColumns) * numCharRows);
    for (size_t i = 0; i < cellPixels.size(); i++) {
        cellPixels[i] = vc::argbToRgba(static_cast<uint32_t>(characterColors[i]));
    }
    vc::parallelRows(numThreads, outputHeight, [&](int start, int end) {
        for (int y = start; y < end; y++) {
            uint32_t* row = outputPixels + static_cast<size_t>(y) * pixelStride;
            std::fill(row, row + outputWidth, black);
        }
    });
    vc::parallelRows(numThreads, numCharRows, kBlockRowsPerChunk, [&](int start, int end) {
        renderBlockRows(templatePixels, templateWidth, templateHeight, charWidth, charHeight,
                        characterIndices, cellPixels.data(), numCharColumns, isPortrait,
                        xFlipped, yFlipped, outputWidth, outputHeight, outputPixels,
                        pixelStride, start, end);
    });
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
