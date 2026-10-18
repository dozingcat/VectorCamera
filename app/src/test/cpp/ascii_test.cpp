// Host test for ascii_effect.cpp: the chunked, bitmap-writing kernels are checked against
// copies of the original kernels (master as of commit e688b42) on random data, across sizes,
// orientations, color modes, thread counts and output strides wider than the image. The
// reference writes ARGB color ints; the new kernels write bitmap pixels, so reference output is
// converted before comparing.
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -Wall -DVC_NO_JNI app/src/test/cpp/ascii_test.cpp \
//       -o $TMPDIR/ascii_test && $TMPDIR/ascii_test

#include "../../main/cpp/ascii_effect.cpp"

#include <cstdio>
#include <random>
#include <string>
#include <vector>

namespace reference {

enum ColorMode { FIXED = 0, PRIMARY = 1, FULL = 2 };

// The original kernel had its own BT.601 constants here; the reference now uses the shared
// conversion the kernel switched to.
inline int yuvToArgb(int y, int u, int v) {
    return static_cast<int>(YuvUtils::yuvToRgbFixed(y, u, v, true));
}

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

// Body of the original computeAsciiDataNativeFromPlanes JNI function.
void computeAsciiData(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
                      int width, int height, int numCharCols, int numCharRows, bool isPortrait,
                      int colorMode, int pixelCharsLength, int textColor,
                      int* characterIndices, int* characterColors) {
    const int uvSize = (width / 2) * (height / 2);
    for (int blockY = 0; blockY < numCharRows; ++blockY) {
        for (int blockX = 0; blockX < numCharCols; ++blockX) {
            const int blockIndex = blockY * numCharCols + blockX;
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
            for (int yy = ymin; yy < ymax; ++yy) {
                for (int xx = xmin; xx < xmax; ++xx) {
                    if (xx >= 0 && xx < width && yy >= 0 && yy < height) {
                        const int yIndex = yy * width + xx;
                        const int uvIndex = (yy / 2) * (width / 2) + (xx / 2);
                        const int y = yData[yIndex] & 0xFF;
                        const int u = (uvIndex < uvSize) ? (uData[uvIndex] & 0xFF) : 128;
                        const int v = (uvIndex < uvSize) ? (vData[uvIndex] & 0xFF) : 128;
                        brightnessTotal += y;
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
            if (pixelCount > 0) {
                const int avgBrightness = static_cast<int>(brightnessTotal / pixelCount);
                const int charIndex = std::min(pixelCharsLength - 1,
                    static_cast<int>((static_cast<double>(avgBrightness) / 255.0) * pixelCharsLength));
                characterIndices[blockIndex] = charIndex;
                int color;
                switch (colorMode) {
                    case FIXED: color = textColor; break;
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
                    default: color = textColor; break;
                }
                characterColors[blockIndex] = color;
            } else {
                characterIndices[blockIndex] = 0;
                characterColors[blockIndex] = textColor;
            }
        }
    }
}

void renderAsciiCharacterGrid(
    const uint32_t* templatePixels, int templateWidth, int templateHeight, int charWidth,
    int charHeight, const int* characterIndices, const uint32_t* characterColors,
    int numCharColumns, int numCharRows, bool isPortrait, int outputWidth, int outputHeight,
    uint32_t* outputPixels, uint32_t backgroundColor, int startRow, int endRow) {
    for (int blockY = startRow; blockY < endRow; blockY++) {
        for (int blockX = 0; blockX < numCharColumns; blockX++) {
            const int cellIndex = blockY * numCharColumns + blockX;
            const int charIndex = characterIndices[cellIndex];
            const uint32_t color = characterColors[cellIndex];
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
                for (int dx = 0; dx < dstWidth; dx++) {
                    int srcX, srcY;
                    if (isPortrait) {
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
                        const uint8_t bgRed = (backgroundColor >> 16) & 0xFF;
                        const uint8_t bgGreen = (backgroundColor >> 8) & 0xFF;
                        const uint8_t bgBlue = backgroundColor & 0xFF;
                        const uint32_t outputColor = (templateRed != bgRed ||
                                                    templateGreen != bgGreen ||
                                                    templateBlue != bgBlue) ?
                            color : backgroundColor;
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

}  // namespace reference

namespace {

int failures = 0;
std::mt19937 rng(4242);

void check(bool ok, const std::string& what) {
    if (!ok) {
        failures++;
        std::printf("FAIL: %s\n", what.c_str());
    }
}

}  // namespace

int main() {
    struct Case { int width, height, cols, rows, charW, charH, outW, outH, stride; bool portrait; };
    // Output sizes come from the Kotlin text metrics; in portrait the grid is rotated so the
    // output is (rows * charH) wide and (cols * charW) tall. Some cases make the output smaller
    // than the grid needs, to exercise the bounds checks.
    const Case cases[] = {
        {64, 48, 8, 6, 8, 8, 64, 48, 64, false},
        {64, 48, 8, 6, 8, 8, 64, 48, 70, false},
        {48, 64, 8, 6, 8, 8, 48, 64, 53, true},
        {97, 61, 13, 7, 7, 9, 91, 63, 91, false},
        {61, 97, 13, 7, 7, 9, 63, 91, 100, true},
        {100, 80, 20, 10, 5, 8, 90, 70, 95, false},   // output smaller than the grid
        {33, 21, 1, 1, 33, 21, 33, 21, 40, false},
    };
    const int threadCounts[] = {1, 2, 5};
    const int numChars = 10;
    for (const Case& c : cases) {
        std::vector<uint8_t> y(c.width * c.height), u((c.width / 2) * (c.height / 2) + 1), v(u.size());
        for (auto& p : y) p = rng() & 0xFF;
        for (auto& p : u) p = rng() & 0xFF;
        for (auto& p : v) p = rng() & 0xFF;
        // Template: numChars glyphs side by side, random pixels that are either the background
        // color or something else.
        const int templateWidth = numChars * c.charW, templateHeight = c.charH;
        const uint32_t background = 0xFF000000u | (rng() & 0xFFFFFF);
        std::vector<uint32_t> templ(templateWidth * templateHeight);
        for (auto& p : templ) p = (rng() % 3 == 0) ? background : (0xFF000000u | (rng() & 0xFFFFFF));
        const int numCells = c.cols * c.rows;
        for (int threads : threadCounts) {
            for (int colorMode = 0; colorMode < 3; colorMode++) {
                char label[160];
                std::snprintf(label, sizeof(label), "%dx%d grid %dx%d portrait=%d mode=%d threads=%d stride=%d",
                              c.width, c.height, c.cols, c.rows, c.portrait, colorMode, threads, c.stride);
                std::vector<int> expIdx(numCells), expCol(numCells), idx(numCells), col(numCells);
                reference::computeAsciiData(y.data(), u.data(), v.data(), c.width, c.height, c.cols, c.rows,
                                            c.portrait, colorMode, numChars, 0xFF123456, expIdx.data(), expCol.data());
                ascii::computeAsciiData(y.data(), u.data(), v.data(), c.width, c.height, c.cols, c.rows,
                                        c.portrait, colorMode, numChars, 0xFF123456, idx.data(), col.data(), threads);
                check(expIdx == idx && expCol == col, std::string("cell data ") + label);

                std::vector<uint32_t> expected(c.outW * c.outH, background);
                reference::renderAsciiCharacterGrid(templ.data(), templateWidth, templateHeight, c.charW, c.charH,
                                                    expIdx.data(), reinterpret_cast<const uint32_t*>(expCol.data()),
                                                    c.cols, c.rows, c.portrait, c.outW, c.outH, expected.data(),
                                                    background, 0, c.rows);
                std::vector<uint32_t> actual(c.stride * c.outH, 0x12345678u);
                ascii::renderCharacterGrid(templ.data(), templateWidth, templateHeight, c.charW, c.charH,
                                           expIdx.data(), expCol.data(), c.cols, c.rows, c.portrait, c.outW, c.outH,
                                           actual.data(), c.stride, background, threads);
                bool ok = true;
                for (int yy = 0; yy < c.outH && ok; yy++) {
                    for (int x = 0; x < c.stride; x++) {
                        uint32_t want = x < c.outW ? vc::argbToRgba(expected[yy * c.outW + x]) : 0x12345678u;
                        if (actual[yy * c.stride + x] != want) { ok = false; break; }
                    }
                }
                check(ok, std::string("render ") + label);
            }
        }
    }
    if (failures == 0) std::printf("All ascii cases passed\n");
    return failures == 0 ? 0 : 1;
}
