// Host test for matrix_effect_native.cpp: the chunked, bitmap-writing kernels are checked
// against copies of the original kernels (master as of commit e688b42) on random data, across
// sizes, orientations, flips, thread counts and output strides wider than the image. The
// reference writes ARGB color ints; the new kernels write bitmap pixels, so reference output is
// converted before comparing.
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -Wall -DVC_NO_JNI app/src/test/cpp/matrix_test.cpp \
//       -o $TMPDIR/matrix_test && $TMPDIR/matrix_test

#include "../../main/cpp/matrix_effect_native.cpp"

#include <cstdio>
#include <random>
#include <string>
#include <vector>

namespace reference {

inline int clamp(int value, int min_val, int max_val) {
    return std::max(min_val, std::min(value, max_val));
}

void computeBlockBrightness(const uint8_t* yData, int imageWidth, int imageHeight,
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

void applyEdgeDetection(const uint8_t* input, uint8_t* output, int width, int height,
                        int multiplier, int startRow, int endRow) {
    for (int y = startRow; y < endRow; y++) {
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

void renderCharacterGrid(
    const uint32_t* templatePixels, int templateWidth, int templateHeight, int charWidth,
    int charHeight, const int* characterIndices, const uint32_t* characterColors,
    int numCharColumns, int numCharRows, bool isPortrait, bool xFlipped, bool yFlipped,
    int outputWidth, int outputHeight, uint32_t* outputPixels, int startRow, int endRow) {
    const uint32_t BLACK = 0xFF000000;
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
                        const uint32_t outputColor = (red > 0 || green > 0 || blue > 0) ? color : BLACK;
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
std::mt19937 rng(9001);

void check(bool ok, const std::string& what) {
    if (!ok) {
        failures++;
        std::printf("FAIL: %s\n", what.c_str());
    }
}

}  // namespace

int main() {
    struct Case { int width, height, cols, rows, charW, charH, outW, outH, stride; bool portrait; };
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
    const int numChars = 12;
    for (const Case& c : cases) {
        std::vector<uint8_t> y(c.width * c.height);
        for (auto& p : y) p = rng() & 0xFF;
        const int numCells = c.cols * c.rows;
        const int templateWidth = numChars * c.charW, templateHeight = c.charH;
        std::vector<uint32_t> templ(templateWidth * templateHeight);
        for (auto& p : templ) p = (rng() % 3 == 0) ? 0xFF000000u : (0xFF000000u | (rng() & 0xFFFFFF));
        std::vector<int> idx(numCells), col(numCells);
        for (auto& i : idx) i = rng() % numChars;
        for (auto& k : col) k = static_cast<int>(0xFF000000u | (rng() & 0xFFFFFF));
        for (int threads : threadCounts) {
            char label[160];
            std::snprintf(label, sizeof(label), "%dx%d grid %dx%d portrait=%d threads=%d stride=%d",
                          c.width, c.height, c.cols, c.rows, c.portrait, threads, c.stride);
            std::vector<uint8_t> expAvg(numCells), avg(numCells);
            reference::computeBlockBrightness(y.data(), c.width, c.height, c.cols, c.rows, c.portrait, expAvg.data(), 0, c.rows);
            matrix::computeBlockBrightness(y.data(), c.width, c.height, c.cols, c.rows, c.portrait, avg.data(), threads);
            check(expAvg == avg, std::string("block brightness ") + label);

            std::vector<uint8_t> expEdges(numCells), edges(numCells);
            reference::applyEdgeDetection(expAvg.data(), expEdges.data(), c.cols, c.rows, 4, 0, c.rows);
            matrix::applyEdgeDetection(avg.data(), edges.data(), c.cols, c.rows, 4);
            check(expEdges == edges, std::string("edge detection ") + label);

            for (int flips = 0; flips < 4; flips++) {
                const bool xFlip = flips & 1, yFlip = flips & 2;
                std::vector<uint32_t> expected(c.outW * c.outH, 0xFF000000u);
                reference::renderCharacterGrid(templ.data(), templateWidth, templateHeight, c.charW, c.charH,
                                               idx.data(), reinterpret_cast<const uint32_t*>(col.data()), c.cols, c.rows,
                                               c.portrait, xFlip, yFlip, c.outW, c.outH, expected.data(), 0, c.rows);
                std::vector<uint32_t> actual(c.stride * c.outH, 0x12345678u);
                matrix::renderCharacterGrid(templ.data(), templateWidth, templateHeight, c.charW, c.charH,
                                            idx.data(), col.data(), c.cols, c.rows, c.portrait, xFlip, yFlip,
                                            c.outW, c.outH, actual.data(), c.stride, threads);
                bool ok = true;
                for (int yy = 0; yy < c.outH && ok; yy++) {
                    for (int x = 0; x < c.stride; x++) {
                        uint32_t want = x < c.outW ? vc::argbToRgba(expected[yy * c.outW + x]) : 0x12345678u;
                        if (actual[yy * c.stride + x] != want) { ok = false; break; }
                    }
                }
                std::snprintf(label, sizeof(label), "render %dx%d portrait=%d xflip=%d yflip=%d threads=%d stride=%d",
                              c.outW, c.outH, c.portrait, xFlip, yFlip, threads, c.stride);
                check(ok, label);
            }
        }
    }
    if (failures == 0) std::printf("All matrix cases passed\n");
    return failures == 0 ? 0 : 1;
}
