// Host test for oil_painting_effect_native.cpp.
//
// The brush sizing and color conversion are checked against verbatim copies of the original
// kernel's code (commit e688b42; the original computed the brightness variance in doubles, the
// rewrite in integers, so a mismatch could only come from a rounding edge). The downscaling is
// checked against a port of the Kotlin resizeImageBytes. The painted color is checked against a
// brute-force count of the colors in each pixel's brush disc, with the rewrite's tie-breaking
// rule (the smallest hash among the most common colors).
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -Wall -DVC_NO_JNI app/src/test/cpp/oil_painting_test.cpp \
//       -o $TMPDIR/oil_painting_test && $TMPDIR/oil_painting_test

#include "../../main/cpp/oil_painting_effect_native.cpp"

#include <cstdio>
#include <random>
#include <string>

namespace reference {

// Port of resizeImageBytes in app/src/main/java/com/dozingcatsoftware/util/images.kt.
std::vector<uint8_t> resizeImageBytes(const std::vector<uint8_t>& data, int oldWidth, int oldHeight,
                                      int newWidth, int newHeight) {
    std::vector<uint8_t> result(static_cast<size_t>(newWidth) * newHeight);
    const float xRatio = static_cast<float>(oldWidth) / newWidth;
    const float yRatio = static_cast<float>(oldHeight) / newHeight;
    for (int y = 0; y < newHeight; y++) {
        for (int x = 0; x < newWidth; x++) {
            const float px = x * xRatio;
            const float py = y * yRatio;
            const int x1 = static_cast<int>(px);
            const int y1 = static_cast<int>(py);
            const int x2 = std::min(x1 + 1, oldWidth - 1);
            const int y2 = std::min(y1 + 1, oldHeight - 1);
            const float fx = px - x1;
            const float fy = py - y1;
            const int p1 = data[y1 * oldWidth + x1];
            const int p2 = data[y1 * oldWidth + x2];
            const int p3 = data[y2 * oldWidth + x1];
            const int p4 = data[y2 * oldWidth + x2];
            volatile float t1 = p1 * (1 - fx) * (1 - fy);
            volatile float t2 = p2 * fx * (1 - fy);
            volatile float t3 = p3 * (1 - fx) * fy;
            volatile float t4 = p4 * fx * fy;
            volatile float s12 = t1 + t2;
            volatile float s123 = s12 + t3;
            const float interpolated = s123 + t4;
            result[y * newWidth + x] = static_cast<uint8_t>(static_cast<int>(interpolated));
        }
    }
    return result;
}

inline uint32_t yuvToRgbQuantized(int y, int u, int v, int quantizationStep) {
    uint32_t rgb = YuvUtils::yuvToRgbFixed(y, u, v);
    uint32_t r = rgb >> 16;
    uint32_t g = (rgb >> 8) & 0xFF;
    uint32_t b = rgb & 0xFF;
    r = (r / quantizationStep) * quantizationStep;
    g = (g / quantizationStep) * quantizationStep;
    b = (b / quantizationStep) * quantizationStep;
    return (0xFF << 24) | (r << 16) | (g << 8) | b;
}

inline double calculateBrightness(uint32_t pixel) {
    uint32_t r = (pixel >> 16) & 0xFF;
    uint32_t g = (pixel >> 8) & 0xFF;
    uint32_t b = pixel & 0xFF;
    return r * 0.299 + g * 0.587 + b * 0.114;
}

int calculateAdaptiveBrushSize(const uint32_t* pixels, int centerX, int centerY, int width, int height,
                               int maxBrushSize, float contrastSensitivity) {
    const int sampleRadius = 3;
    double totalVariance = 0.0;
    int sampleCount = 0;
    uint32_t centerPixel = pixels[centerY * width + centerX];
    double centerBrightness = calculateBrightness(centerPixel);
    for (int dy = -sampleRadius; dy <= sampleRadius; dy++) {
        for (int dx = -sampleRadius; dx <= sampleRadius; dx++) {
            int sampleX = centerX + dx;
            int sampleY = centerY + dy;
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                uint32_t samplePixel = pixels[sampleY * width + sampleX];
                double sampleBrightness = calculateBrightness(samplePixel);
                double diff = sampleBrightness - centerBrightness;
                totalVariance += diff * diff;
                sampleCount++;
            }
        }
    }
    double avgVariance = (sampleCount > 0) ? totalVariance / sampleCount : 0.0;
    double normalizedVariance = std::min(1.0, avgVariance / (255.0 * 255.0));
    double adaptiveFactor = 1.0 - std::min(0.8, normalizedVariance * contrastSensitivity);
    int adaptiveBrushSize = static_cast<int>(maxBrushSize * adaptiveFactor);
    return std::max(2, std::min(maxBrushSize, adaptiveBrushSize));
}

inline uint32_t hashQuantizedColor(uint32_t color) {
    uint32_t r = (color >> 19) & 0x1F;
    uint32_t g = (color >> 11) & 0x1F;
    uint32_t b = (color >> 3) & 0x1F;
    return (r << 10) | (g << 5) | b;
}

// Expected output: brute-force histogram of the disc with the rewrite's tie-breaking.
uint32_t expectedPixel(const std::vector<uint32_t>& rgb, int width, int height, int x, int y, int radius,
                       const oil::ColorTables& tables) {
    std::vector<int> counts(oil::kNumHashes, 0);
    for (int dy = -radius; dy <= radius; dy++) {
        for (int dx = -radius; dx <= radius; dx++) {
            const int sx = x + dx, sy = y + dy;
            if (dx * dx + dy * dy <= radius * radius && sx >= 0 && sx < width && sy >= 0 && sy < height) {
                counts[hashQuantizedColor(rgb[sy * width + sx])]++;
            }
        }
    }
    int best = -1, bestCount = 0;
    for (int h = 0; h < oil::kNumHashes; h++) {
        if (counts[h] > bestCount) { bestCount = counts[h]; best = h; }
    }
    return tables.bitmapColor(static_cast<uint16_t>(best));
}

// Expected output image: scale as the Kotlin code does, convert as the original kernel did, size
// the brush as the original kernel did, and pick each pixel's color by brute force.
std::vector<uint32_t> expectedImage(const std::vector<uint8_t>& y, const std::vector<uint8_t>& u,
                                    const std::vector<uint8_t>& v, int srcW, int srcH, int w, int h,
                                    int brush, int levels, float sens) {
    const int srcUvW = (srcW + 1) / 2, srcUvH = (srcH + 1) / 2, uvW = (w + 1) / 2, uvH = (h + 1) / 2;
    const std::vector<uint8_t> sy = resizeImageBytes(y, srcW, srcH, w, h);
    const std::vector<uint8_t> su = resizeImageBytes(u, srcUvW, srcUvH, uvW, uvH);
    const std::vector<uint8_t> sv = resizeImageBytes(v, srcUvW, srcUvH, uvW, uvH);
    const int step = 256 / levels;
    std::vector<uint32_t> rgb(static_cast<size_t>(w) * h);
    for (int row = 0; row < h; row++)
        for (int x = 0; x < w; x++)
            rgb[row * w + x] = yuvToRgbQuantized(sy[row * w + x], su[(row / 2) * uvW + x / 2],
                                                 sv[(row / 2) * uvW + x / 2], step);
    const oil::ColorTables tables(levels);
    std::vector<uint32_t> out(static_cast<size_t>(w) * h);
    for (int row = 0; row < h; row++) {
        for (int x = 0; x < w; x++) {
            const int radius = std::min(oil::kMaxBrushRadius,
                                        calculateAdaptiveBrushSize(rgb.data(), x, row, w, h, brush, sens));
            out[row * w + x] = expectedPixel(rgb, w, h, x, row, radius, tables);
        }
    }
    return out;
}

}  // namespace reference

int main() {
    std::mt19937 rng(4242);
    int failures = 0;
    struct Size { int srcW, srcH, w, h, stride; };
    const Size sizes[] = {{1, 1, 1, 1, 1}, {3, 2, 3, 2, 5}, {13, 11, 13, 11, 13}, {40, 33, 40, 33, 48},
                          {97, 61, 97, 61, 100}, {160, 90, 97, 55, 97}, {203, 77, 61, 23, 70}, {50, 50, 7, 7, 9}};
    const int brushSizes[] = {2, 4, 6, 9};
    const int levelsList[] = {8, 32, 48};
    const float sensitivities[] = {0.5f, 2.0f, 5.0f};
    const int threadCounts[] = {1, 2, 5};
    int cases = 0;
    for (const Size& s : sizes) {
        const int uvWidth = (s.srcW + 1) / 2, uvHeight = (s.srcH + 1) / 2;
        std::vector<uint8_t> y(s.srcW * s.srcH), u(uvWidth * uvHeight), v(uvWidth * uvHeight);
        // Smooth regions with a sprinkling of noise, so the adaptive brush size varies.
        for (int row = 0; row < s.srcH; row++)
            for (int x = 0; x < s.srcW; x++)
                y[row * s.srcW + x] = static_cast<uint8_t>((x * 200 / std::max(1, s.srcW) + (rng() % 50)) & 0xFF);
        for (auto& p : u) p = 100 + rng() % 60;
        for (auto& p : v) p = 90 + rng() % 80;
        for (int brush : brushSizes) {
            for (int levels : levelsList) {
                for (float sens : sensitivities) {
                    const std::vector<uint32_t> expected = reference::expectedImage(
                            y, u, v, s.srcW, s.srcH, s.w, s.h, brush, levels, sens);
                    for (int threads : threadCounts) {
                        std::vector<uint32_t> actual(s.stride * s.h, 0x12345678u);
                        oil::processImage(y.data(), u.data(), v.data(), s.srcW, s.srcH, s.w, s.h, brush,
                                          levels, sens, actual.data(), s.stride, threads);
                        cases++;
                        bool ok = true;
                        for (int row = 0; row < s.h && ok; row++) {
                            for (int x = 0; x < s.stride; x++) {
                                uint32_t want = x < s.w ? expected[row * s.w + x] : 0x12345678u;
                                if (actual[row * s.stride + x] != want) {
                                    std::printf("FAIL: %dx%d->%dx%d brush=%d levels=%d sens=%.1f threads=%d at (%d,%d): got %08x want %08x\n",
                                                s.srcW, s.srcH, s.w, s.h, brush, levels, sens, threads, x, row,
                                                actual[row * s.stride + x], want);
                                    ok = false;
                                    break;
                                }
                            }
                        }
                        if (!ok) failures++;
                    }
                }
            }
        }
    }
    if (failures == 0) std::printf("All %d oil painting cases match the reference\n", cases);
    return failures == 0 ? 0 : 1;
}
