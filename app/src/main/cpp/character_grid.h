// Shared pieces of the character-grid effects (ASCII and Matrix): the mapping from a character
// cell to its region of the input image, and stamping a character template into the output
// bitmap for every cell.
//
// Both effects divide the image into numCharColumns x numCharRows cells. In landscape, cell
// (blockX, blockY) is the input rectangle at the same position; in portrait, the camera image is
// rotated, so the cell maps to a rotated rectangle and the rendered characters are rotated too.
#ifndef VECTORCAMERA_CHARACTER_GRID_H
#define VECTORCAMERA_CHARACTER_GRID_H

#include <algorithm>
#include <cstdint>
#include <vector>

#include "pixel_format.h"
#include "worker_pool.h"

namespace vc {

// Character cell rows per work chunk for parallelRows; each block row covers many image rows.
constexpr int kCharacterRowsPerChunk = 1;

// Input image rectangle averaged for one character cell, [xmin, xmax) x [ymin, ymax). Not clipped
// to the image; callers check bounds per pixel.
struct CellInputRect {
    int xmin, xmax, ymin, ymax;
};

inline CellInputRect cellInputRect(int imageWidth, int imageHeight, int numCharColumns,
                                   int numCharRows, bool isPortrait, int blockX, int blockY) {
    CellInputRect r;
    if (isPortrait) {
        const int inputPixelsPerCol = imageHeight / numCharColumns;
        const int inputPixelsPerRow = imageWidth / numCharRows;
        r.xmin = blockY * inputPixelsPerRow;
        r.xmax = r.xmin + inputPixelsPerRow;
        r.ymin = (numCharColumns - 1 - blockX) * inputPixelsPerCol;
        r.ymax = r.ymin + inputPixelsPerCol;
    } else {
        const int inputPixelsPerCol = imageWidth / numCharColumns;
        const int inputPixelsPerRow = imageHeight / numCharRows;
        r.xmin = blockX * inputPixelsPerCol;
        r.xmax = r.xmin + inputPixelsPerCol;
        r.ymin = blockY * inputPixelsPerRow;
        r.ymax = r.ymin + inputPixelsPerRow;
    }
    return r;
}

// Stamps the character template into the output for the cells of block rows [startRow, endRow).
//
// The template is a strip of characters, each charWidth x charHeight, in ARGB; cell `i` draws
// character characterIndices[i]. Each template pixel whose RGB differs from `backgroundColor`
// (ARGB) is written as cellPixels[i], the rest as `backgroundPixel`; both are already converted
// bitmap pixels (see pixel_format.h). In portrait the character is rotated -90 degrees; xFlipped
// and yFlipped mirror it. Output rows are `pixelStride` uint32s apart.
inline void renderCharacterRows(const uint32_t* templatePixels, int templateWidth,
                                int templateHeight, int charWidth, int charHeight,
                                const int32_t* characterIndices, const uint32_t* cellPixels,
                                int numCharColumns, bool isPortrait, bool xFlipped, bool yFlipped,
                                int outputWidth, int outputHeight, uint32_t* outputPixels,
                                int pixelStride, uint32_t backgroundColor, uint32_t backgroundPixel,
                                int startRow, int endRow) {
    const uint32_t bgRgb = backgroundColor & 0xFFFFFFu;
    for (int blockY = startRow; blockY < endRow; blockY++) {
        for (int blockX = 0; blockX < numCharColumns; blockX++) {
            const int cellIndex = blockY * numCharColumns + blockX;
            const int charIndex = characterIndices[cellIndex];
            const uint32_t color = cellPixels[cellIndex];

            const int srcLeft = charIndex * charWidth;
            const int srcRight = srcLeft + charWidth;

            int dstLeft, dstTop, dstWidth, dstHeight;
            if (isPortrait) {
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
                        srcX = srcLeft + (yFlipped ? dy : charWidth - 1 - dy);
                        srcY = xFlipped ? charHeight - 1 - dx : dx;
                    } else {
                        srcX = srcLeft + (xFlipped ? charWidth - 1 - dx : dx);
                        srcY = yFlipped ? charHeight - 1 - dy : dy;
                    }
                    if (srcX >= srcLeft && srcX < srcRight && srcY >= 0 && srcY < templateHeight) {
                        const uint32_t templateRgb = templatePixels[srcY * templateWidth + srcX] & 0xFFFFFFu;
                        const uint32_t outputPixel = templateRgb != bgRgb ? color : backgroundPixel;
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
// (see pixel_format.h), rows `pixelStride` uint32s apart. See renderCharacterRows for the rest.
inline void renderCharacterGrid(const uint32_t* templatePixels, int templateWidth,
                                int templateHeight, int charWidth, int charHeight,
                                const int32_t* characterIndices, const int32_t* characterColors,
                                int numCharColumns, int numCharRows, bool isPortrait,
                                bool xFlipped, bool yFlipped, int outputWidth, int outputHeight,
                                uint32_t* outputPixels, int pixelStride, uint32_t backgroundColor,
                                int numThreads) {
    const uint32_t backgroundPixel = argbToRgba(backgroundColor);
    std::vector<uint32_t> cellPixels(static_cast<size_t>(numCharColumns) * numCharRows);
    for (size_t i = 0; i < cellPixels.size(); i++) {
        cellPixels[i] = argbToRgba(static_cast<uint32_t>(characterColors[i]));
    }
    parallelRows(numThreads, outputHeight, [&](int start, int end) {
        for (int y = start; y < end; y++) {
            uint32_t* row = outputPixels + static_cast<size_t>(y) * pixelStride;
            std::fill(row, row + outputWidth, backgroundPixel);
        }
    });
    parallelRows(numThreads, numCharRows, kCharacterRowsPerChunk, [&](int start, int end) {
        renderCharacterRows(templatePixels, templateWidth, templateHeight, charWidth, charHeight,
                            characterIndices, cellPixels.data(), numCharColumns, isPortrait,
                            xFlipped, yFlipped, outputWidth, outputHeight, outputPixels,
                            pixelStride, backgroundColor, backgroundPixel, start, end);
    });
}

}  // namespace vc

#endif  // VECTORCAMERA_CHARACTER_GRID_H
