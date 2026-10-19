// Host test for cartoon_effect_native.cpp: the rewritten kernel is checked against a verbatim
// copy of the original (commit e688b42) on random images, across blur radii, thread counts and
// output strides. The reference writes ARGB color ints; the new kernel writes bitmap pixels, so
// the reference output is converted before comparing.
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -Wall -DVC_NO_JNI app/src/test/cpp/cartoon_test.cpp \
//       -o /tmp/cartoon_test && /tmp/cartoon_test

#include "../../main/cpp/cartoon_effect_native.cpp"

#include <cstdio>
#include <random>

namespace reference {

// --- cartoon_effect_native.cpp as of commit e688b42 (kernels only) ---
inline void createColorLUT(int* lut) {
    for (int index = 0; index < 256; index++) {
        int mapval = static_cast<int>(round(index / 85.0)) * 85;
        lut[index] = mapval;
    }
}

void processRows(int startY, int endY, int width, int height, const unsigned char* yData,
                 const unsigned char* uData, const unsigned char* vData, int uvWidth, int* pixels,
                 const int* colorLUT) {
    for (int y = startY; y < endY; y++) {
        for (int x = 0; x < width; x++) {
            int pixelIndex = y * width + x;
            int yy = yData[pixelIndex] & 0xFF;
            int uvX = x / 2;
            int uvY = y / 2;
            int uvIndex = uvY * uvWidth + uvX;
            int u = uData[uvIndex] & 0xFF;
            int v = vData[uvIndex] & 0xFF;
            int rgb = YuvUtils::yuvToRgb(yy, u, v, false);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int quantizedR = colorLUT[r];
            int quantizedG = colorLUT[g];
            int quantizedB = colorLUT[b];
            pixels[pixelIndex] = (255 << 24) | (quantizedR << 16) | (quantizedG << 8) | quantizedB;
        }
    }
}

void applyHorizontalBlur(const int* input, int* output, int width, int height, int radius) {
    const int kernelSize = radius * 2 + 1;
    for (int y = 0; y < height; y++) {
        int totalR = 0, totalG = 0, totalB = 0;
        for (int kx = -radius; kx <= radius; kx++) {
            int sampleX = std::max(0, std::min(width - 1, kx));
            int samplePixel = input[y * width + sampleX];
            totalR += (samplePixel >> 16) & 0xFF;
            totalG += (samplePixel >> 8) & 0xFF;
            totalB += samplePixel & 0xFF;
        }
        for (int x = 0; x < width; x++) {
            int avgR = totalR / kernelSize;
            int avgG = totalG / kernelSize;
            int avgB = totalB / kernelSize;
            output[y * width + x] = (255 << 24) | (avgR << 16) | (avgG << 8) | avgB;
            if (x < width - 1) {
                int leftX = std::max(0, std::min(width - 1, x - radius));
                int leftPixel = input[y * width + leftX];
                totalR -= (leftPixel >> 16) & 0xFF;
                totalG -= (leftPixel >> 8) & 0xFF;
                totalB -= leftPixel & 0xFF;
                int rightX = std::max(0, std::min(width - 1, x + radius + 1));
                int rightPixel = input[y * width + rightX];
                totalR += (rightPixel >> 16) & 0xFF;
                totalG += (rightPixel >> 8) & 0xFF;
                totalB += rightPixel & 0xFF;
            }
        }
    }
}

void applyVerticalBlur(const int* input, int* output, int width, int height, int radius) {
    const int kernelSize = radius * 2 + 1;
    for (int x = 0; x < width; x++) {
        int totalR = 0, totalG = 0, totalB = 0;
        for (int ky = -radius; ky <= radius; ky++) {
            int sampleY = std::max(0, std::min(height - 1, ky));
            int samplePixel = input[sampleY * width + x];
            totalR += (samplePixel >> 16) & 0xFF;
            totalG += (samplePixel >> 8) & 0xFF;
            totalB += samplePixel & 0xFF;
        }
        for (int y = 0; y < height; y++) {
            int avgR = totalR / kernelSize;
            int avgG = totalG / kernelSize;
            int avgB = totalB / kernelSize;
            output[y * width + x] = (255 << 24) | (avgR << 16) | (avgG << 8) | avgB;
            if (y < height - 1) {
                int topY = std::max(0, std::min(height - 1, y - radius));
                int topPixel = input[topY * width + x];
                totalR -= (topPixel >> 16) & 0xFF;
                totalG -= (topPixel >> 8) & 0xFF;
                totalB -= topPixel & 0xFF;
                int bottomY = std::max(0, std::min(height - 1, y + radius + 1));
                int bottomPixel = input[bottomY * width + x];
                totalR += (bottomPixel >> 16) & 0xFF;
                totalG += (bottomPixel >> 8) & 0xFF;
                totalB += bottomPixel & 0xFF;
            }
        }
    }
}

void cartoon(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width,
             int height, int blurRadius, int* pixels) {
    int uvWidth = (width + 1) / 2;
    int colorLUT[256];
    createColorLUT(colorLUT);
    processRows(0, height, width, height, yData, uData, vData, uvWidth, pixels, colorLUT);
    if (blurRadius > 0) {
        std::vector<int> tempPixels(width * height);
        applyHorizontalBlur(pixels, tempPixels.data(), width, height, blurRadius);
        applyVerticalBlur(tempPixels.data(), pixels, width, height, blurRadius);
    }
}

}  // namespace reference

int main() {
    std::mt19937 rng(9001);
    int failures = 0;
    struct Size { int w, h, stride; };
    const Size sizes[] = {{1, 1, 1}, {2, 3, 5}, {17, 9, 20}, {64, 70, 64}, {131, 101, 140}};
    for (const Size& s : sizes) {
        const int uvWidth = (s.w + 1) / 2, uvHeight = (s.h + 1) / 2;
        std::vector<uint8_t> y(s.w * s.h), u(uvWidth * uvHeight), v(uvWidth * uvHeight);
        for (auto& p : y) p = rng() & 0xFF;
        for (auto& p : u) p = rng() & 0xFF;
        for (auto& p : v) p = rng() & 0xFF;
        for (int radius : {0, 1, 2, 5}) {
            std::vector<int> expected(s.w * s.h);
            reference::cartoon(y.data(), u.data(), v.data(), s.w, s.h, radius, expected.data());
            for (int threads : {1, 2, 5}) {
                std::vector<uint32_t> actual(s.stride * s.h, 0xFFFFFFFFu);
                cartoon::processImage(y.data(), u.data(), v.data(), s.w, s.h, radius, actual.data(), s.stride, threads);
                bool ok = true;
                for (int row = 0; row < s.h && ok; row++) {
                    for (int x = 0; x < s.stride; x++) {
                        uint32_t want = x < s.w ? vc::argbToRgba(expected[row * s.w + x]) : 0xFFFFFFFFu;
                        if (actual[row * s.stride + x] != want) {
                            std::printf("FAIL: %dx%d radius=%d threads=%d at (%d,%d): got %08x want %08x\n",
                                        s.w, s.h, radius, threads, x, row, actual[row * s.stride + x], want);
                            ok = false;
                            break;
                        }
                    }
                }
                if (!ok) failures++;
            }
        }
    }
    if (failures == 0) std::printf("All cartoon cases match the reference\n");
    return failures == 0 ? 0 : 1;
}
