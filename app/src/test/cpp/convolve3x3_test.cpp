// Host test for convolve3x3_effect_native.cpp: the rewritten kernel is checked against a
// verbatim copy of the original (commit e688b42) on random images, across thread counts and
// output strides. The reference writes ARGB color ints; the new kernel writes bitmap pixels, so
// the reference output is converted before comparing.
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -Wall -DVC_NO_JNI app/src/test/cpp/convolve3x3_test.cpp \
//       -o /tmp/convolve3x3_test && /tmp/convolve3x3_test

#include "../../main/cpp/convolve3x3_effect_native.cpp"

#include <cstdio>
#include <random>

namespace reference {

// --- convolve3x3_effect_native.cpp as of commit e688b42 (kernels only) ---
void processConvolutionRows(int startY, int endY, int width, int height,
                            const unsigned char* yData, unsigned char* convolvedData,
                            const float* coefficients) {
    const float c0 = coefficients[0], c1 = coefficients[1], c2 = coefficients[2];
    const float c3 = coefficients[3], c4 = coefficients[4], c5 = coefficients[5];
    const float c6 = coefficients[6], c7 = coefficients[7], c8 = coefficients[8];
    for (int y = startY; y < endY; y++) {
        const unsigned char* rowPtr = yData + y * width;
        unsigned char* outPtr = convolvedData + y * width;
        if (width > 0) {
            const unsigned char* prevRow = (y > 0) ? rowPtr - width : rowPtr;
            const unsigned char* nextRow = (y < height - 1) ? rowPtr + width : rowPtr;
            float sum = c0 * prevRow[0] + c1 * prevRow[0] + c2 * prevRow[1] +
                       c3 * rowPtr[0] + c4 * rowPtr[0] + c5 * rowPtr[1] +
                       c6 * nextRow[0] + c7 * nextRow[0] + c8 * nextRow[1];
            int result = static_cast<int>(sum + 0.5f);
            outPtr[0] = static_cast<unsigned char>(result < 0 ? 0 : (result > 255 ? 255 : result));
        }
        for (int x = 1; x < width - 1; x++) {
            const unsigned char* prevRow = (y > 0) ? rowPtr - width + x : rowPtr + x;
            const unsigned char* currRow = rowPtr + x;
            const unsigned char* nextRow = (y < height - 1) ? rowPtr + width + x : rowPtr + x;
            float sum = c0 * prevRow[-1] + c1 * prevRow[0] + c2 * prevRow[1] +
                       c3 * currRow[-1] + c4 * currRow[0] + c5 * currRow[1] +
                       c6 * nextRow[-1] + c7 * nextRow[0] + c8 * nextRow[1];
            int result = static_cast<int>(sum + 0.5f);
            outPtr[x] = static_cast<unsigned char>(result < 0 ? 0 : (result > 255 ? 255 : result));
        }
        if (width > 1) {
            int x = width - 1;
            const unsigned char* prevRow = (y > 0) ? rowPtr - width + x : rowPtr + x;
            const unsigned char* currRow = rowPtr + x;
            const unsigned char* nextRow = (y < height - 1) ? rowPtr + width + x : rowPtr + x;
            float sum = c0 * prevRow[-1] + c1 * prevRow[0] + c2 * prevRow[0] +
                       c3 * currRow[-1] + c4 * currRow[0] + c5 * currRow[0] +
                       c6 * nextRow[-1] + c7 * nextRow[0] + c8 * nextRow[0];
            int result = static_cast<int>(sum + 0.5f);
            outPtr[x] = static_cast<unsigned char>(result < 0 ? 0 : (result > 255 ? 255 : result));
        }
    }
}

void processColorMappingRows(int startIndex, int endIndex, const unsigned char* convolvedData,
                             int* pixels, const int* colorMap) {
    for (int i = startIndex; i < endIndex; i++) {
        pixels[i] = colorMap[convolvedData[i]];
    }
}

void convolve(const uint8_t* yData, int width, int height, const float* coefficients,
              const int* colorMap, int* pixels) {
    std::vector<unsigned char> convolved(width * height);
    processConvolutionRows(0, height, width, height, yData, convolved.data(), coefficients);
    processColorMappingRows(0, width * height, convolved.data(), pixels, colorMap);
}

}  // namespace reference

int main() {
    std::mt19937 rng(4242);
    int failures = 0;
    // The original kernel reads column 1 even for width 1, so widths start at 2.
    struct Size { int w, h, stride; };
    const Size sizes[] = {{2, 1, 2}, {2, 3, 5}, {17, 9, 20}, {64, 70, 64}, {131, 101, 140}};
    const float kernels[][9] = {
        {0, -1, 0, -1, 5, -1, 0, -1, 0},                 // sharpen
        {1 / 9.f, 1 / 9.f, 1 / 9.f, 1 / 9.f, 1 / 9.f, 1 / 9.f, 1 / 9.f, 1 / 9.f, 1 / 9.f},
        {-2, -1, 0, -1, 1, 1, 0, 1, 2},                  // emboss
        {-1, -1, -1, -1, 8, -1, -1, -1, -1},             // edges
    };
    for (const Size& s : sizes) {
        std::vector<uint8_t> y(s.w * s.h);
        for (auto& p : y) p = rng() & 0xFF;
        std::vector<int> colorMap(256);
        for (auto& c : colorMap) c = static_cast<int>(rng() | 0xFF000000u);
        std::vector<uint32_t> bitmapMap(256);
        for (int i = 0; i < 256; i++) bitmapMap[i] = vc::argbToRgba(colorMap[i]);
        for (const float* k : kernels) {
            std::vector<int> expected(s.w * s.h);
            reference::convolve(y.data(), s.w, s.h, k, colorMap.data(), expected.data());
            for (int threads : {1, 2, 5}) {
                std::vector<uint32_t> actual(s.stride * s.h, 0xFFFFFFFFu);
                convolve3x3::processImage(y.data(), s.w, s.h, k, bitmapMap.data(), actual.data(), s.stride, threads);
                bool ok = true;
                for (int row = 0; row < s.h && ok; row++) {
                    for (int x = 0; x < s.stride; x++) {
                        uint32_t want = x < s.w ? vc::argbToRgba(expected[row * s.w + x]) : 0xFFFFFFFFu;
                        if (actual[row * s.stride + x] != want) {
                            std::printf("FAIL: %dx%d kernel[0]=%g threads=%d at (%d,%d): got %08x want %08x\n",
                                        s.w, s.h, k[0], threads, x, row, actual[row * s.stride + x], want);
                            ok = false;
                            break;
                        }
                    }
                }
                if (!ok) failures++;
            }
        }
    }
    if (failures == 0) std::printf("All convolve3x3 cases match the reference\n");
    return failures == 0 ? 0 : 1;
}
