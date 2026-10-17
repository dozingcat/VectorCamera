// Host test for oil_painting_effect_native.cpp: checks the chunked, bitmap-writing kernel against
// a verbatim copy of the original kernel (commit e688b42) run single-threaded, which is the
// correct reference: the original's multi-threaded path sampled unconverted (zero) pixels across
// band boundaries, which the rewrite fixes.
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -Wall -DVC_NO_JNI app/src/test/cpp/oil_painting_test.cpp \
//       -o $TMPDIR/oil_painting_test && $TMPDIR/oil_painting_test

#include "../../main/cpp/oil_painting_effect_native.cpp"

#include <cstdio>
#include <random>
#include <string>

namespace reference {

struct BrushPattern {
    std::vector<std::pair<int16_t, int16_t>> offsets;
    std::vector<std::pair<int16_t, int16_t>> leftEdgeToRemove;
    std::vector<std::pair<int16_t, int16_t>> rightEdgeToAdd;
};
static std::vector<BrushPattern> brushPatterns;
static bool brushPatternsInitialized = false;

void initializeBrushPatterns() {
    if (brushPatternsInitialized) return;
    const int maxRadius = 20;
    brushPatterns.resize(maxRadius + 1);
    for (int radius = 0; radius <= maxRadius; radius++) {
        BrushPattern& pattern = brushPatterns[radius];
        pattern.offsets.clear();
        if (radius == 0) {
            pattern.offsets.emplace_back(0, 0);
            continue;
        }
        int radiusSquared = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distanceSquared = dx * dx + dy * dy;
                if (distanceSquared <= radiusSquared) {
                    pattern.offsets.emplace_back(static_cast<int16_t>(dx), static_cast<int16_t>(dy));
                }
            }
        }
        pattern.leftEdgeToRemove.clear();
        pattern.rightEdgeToAdd.clear();
        if (radius > 0) {
            for (const auto &p : pattern.offsets) {
                int dx = p.first;
                int dy = p.second;
                int leftDistanceSquared = (dx - 1) * (dx - 1) + dy * dy;
                if (leftDistanceSquared > radiusSquared) {
                    pattern.leftEdgeToRemove.emplace_back(dx - 1, dy);
                }
                int rightDistanceSquared = (dx + 1) * (dx + 1) + dy * dy;
                if (rightDistanceSquared > radiusSquared) {
                    pattern.rightEdgeToAdd.emplace_back(dx, dy);
                }
            }
        }
    }
    brushPatternsInitialized = true;
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

enum ColorCountUpdateType {Full, Incremental};

void updateColorCounts(const uint32_t* pixels, int centerX, int centerY, int width, int height, int radius,
                       std::vector<uint16_t>& colorCounts, std::vector<uint32_t>& usedColors,
                       std::vector<uint32_t>& colorSamples, ColorCountUpdateType updateType) {
    if (!brushPatternsInitialized) initializeBrushPatterns();
    radius = std::max(0, std::min(radius, static_cast<int>(brushPatterns.size()) - 1));
    const BrushPattern& pattern = brushPatterns[radius];
    if (updateType == ColorCountUpdateType::Full) {
        for (const auto& offset : pattern.offsets) {
            int sampleX = centerX + offset.first;
            int sampleY = centerY + offset.second;
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                uint32_t samplePixel = pixels[sampleY * width + sampleX];
                uint32_t colorHash = hashQuantizedColor(samplePixel);
                if (colorCounts[colorHash] == 0) {
                    usedColors.push_back(colorHash);
                    colorSamples.push_back(samplePixel);
                }
                colorCounts[colorHash]++;
            }
        }
    } else {
        for (const auto& offset : pattern.leftEdgeToRemove) {
            int sampleX = centerX + offset.first;
            int sampleY = centerY + offset.second;
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                uint32_t samplePixel = pixels[sampleY * width + sampleX];
                uint32_t colorHash = hashQuantizedColor(samplePixel);
                if (colorCounts[colorHash] > 0) colorCounts[colorHash]--;
            }
        }
        for (const auto& offset : pattern.rightEdgeToAdd) {
            int sampleX = centerX + offset.first;
            int sampleY = centerY + offset.second;
            if (sampleX >= 0 && sampleX < width && sampleY >= 0 && sampleY < height) {
                uint32_t samplePixel = pixels[sampleY * width + sampleX];
                uint32_t colorHash = hashQuantizedColor(samplePixel);
                if (colorCounts[colorHash] == 0) {
                    usedColors.push_back(colorHash);
                    colorSamples.push_back(samplePixel);
                }
                colorCounts[colorHash]++;
            }
        }
    }
}

uint32_t findDominantColorFromCounts(const std::vector<uint16_t>& colorCounts, const std::vector<uint32_t>& usedColors,
                                     const std::vector<uint32_t>& colorSamples, uint32_t fallbackColor) {
    uint32_t dominantColor = fallbackColor;
    int maxCount = 0;
    for (size_t i = 0; i < usedColors.size(); i++) {
        uint32_t colorHash = usedColors[i];
        if (colorCounts[colorHash] > maxCount) {
            maxCount = colorCounts[colorHash];
            dominantColor = colorSamples[i];
        }
    }
    return dominantColor;
}

void processOilPaintingRows(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width, int height,
                            int brushSize, int levels, float contrastSensitivity, uint32_t* outputPixels,
                            int startRow, int endRow) {
    const int uvWidth = (width + 1) / 2;
    const int quantizationStep = 256 / levels;
    std::vector<uint32_t> rgbPixels(width * height);
    for (int y = startRow; y < endRow; y++) {
        for (int x = 0; x < width; x++) {
            int pixelIndex = y * width + x;
            int yy = yData[pixelIndex];
            int uvIndex = (y / 2) * uvWidth + (x / 2);
            rgbPixels[pixelIndex] = yuvToRgbQuantized(yy, uData[uvIndex], vData[uvIndex], quantizationStep);
        }
    }
    std::vector<uint16_t> colorCounts(32768, 0);
    std::vector<uint32_t> usedColors;
    std::vector<uint32_t> colorSamples;
    for (int y = startRow; y < endRow; y++) {
        for (uint32_t colorHash : usedColors) colorCounts[colorHash] = 0;
        usedColors.clear();
        colorSamples.clear();
        int previousBrushSize = -1;
        for (int x = 0; x < width; x++) {
            int pixelIndex = y * width + x;
            int localBrushSize = calculateAdaptiveBrushSize(rgbPixels.data(), x, y, width, height, brushSize, contrastSensitivity);
            if (localBrushSize == previousBrushSize && x > 0) {
                updateColorCounts(rgbPixels.data(), x, y, width, height, localBrushSize,
                                  colorCounts, usedColors, colorSamples, ColorCountUpdateType::Incremental);
            } else {
                if (previousBrushSize != -1) {
                    for (uint32_t colorHash : usedColors) colorCounts[colorHash] = 0;
                    usedColors.clear();
                    colorSamples.clear();
                }
                updateColorCounts(rgbPixels.data(), x, y, width, height, localBrushSize,
                                  colorCounts, usedColors, colorSamples, ColorCountUpdateType::Full);
            }
            outputPixels[pixelIndex] = findDominantColorFromCounts(colorCounts, usedColors, colorSamples, rgbPixels[pixelIndex]);
            previousBrushSize = localBrushSize;
        }
    }
}

}  // namespace reference

int main() {
    std::mt19937 rng(4242);
    int failures = 0;
    struct Size { int w, h, stride; };
    const Size sizes[] = {{1, 1, 1}, {3, 2, 5}, {13, 11, 13}, {40, 33, 48}, {97, 61, 100}};
    const int brushSizes[] = {2, 4, 6};
    const int levelsList[] = {8, 16, 32};
    const float sensitivities[] = {0.5f, 2.0f, 5.0f};
    const int threadCounts[] = {1, 2, 5};
    int cases = 0;
    for (const Size& s : sizes) {
        const int uvWidth = (s.w + 1) / 2, uvHeight = (s.h + 1) / 2;
        std::vector<uint8_t> y(s.w * s.h), u(uvWidth * uvHeight), v(uvWidth * uvHeight);
        // Smooth regions with a sprinkling of noise, so the adaptive brush size varies.
        for (int row = 0; row < s.h; row++)
            for (int x = 0; x < s.w; x++)
                y[row * s.w + x] = static_cast<uint8_t>((x * 200 / std::max(1, s.w) + (rng() % 50)) & 0xFF);
        for (auto& p : u) p = 100 + rng() % 60;
        for (auto& p : v) p = 90 + rng() % 80;
        for (int brush : brushSizes) {
            for (int levels : levelsList) {
                for (float sens : sensitivities) {
                    std::vector<uint32_t> expected(s.w * s.h);
                    reference::processOilPaintingRows(y.data(), u.data(), v.data(), s.w, s.h, brush, levels, sens,
                                                      expected.data(), 0, s.h);
                    for (int threads : threadCounts) {
                        std::vector<uint32_t> actual(s.stride * s.h, 0x12345678u);
                        oil::processImage(y.data(), u.data(), v.data(), s.w, s.h, brush, levels, sens,
                                          actual.data(), s.stride, threads);
                        cases++;
                        bool ok = true;
                        for (int row = 0; row < s.h && ok; row++) {
                            for (int x = 0; x < s.stride; x++) {
                                uint32_t want = x < s.w ? vc::argbToRgba(expected[row * s.w + x]) : 0x12345678u;
                                if (actual[row * s.stride + x] != want) {
                                    std::printf("FAIL: %dx%d brush=%d levels=%d sens=%.1f threads=%d at (%d,%d): got %08x want %08x\n",
                                                s.w, s.h, brush, levels, sens, threads, x, row, actual[row * s.stride + x], want);
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
