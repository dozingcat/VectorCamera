// Host test for stained_glass_effect_native.cpp: checks the chunked, bitmap-writing kernels
// against a verbatim copy of the original kernels (commit e688b42, single-threaded, with the
// push-style edge drawing) on random images.
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -Wall -DVC_NO_JNI app/src/test/cpp/stained_glass_test.cpp \
//       -o $TMPDIR/stained_glass_test && $TMPDIR/stained_glass_test

#include "../../main/cpp/stained_glass_effect_native.cpp"

#include <cstdio>
#include <string>

namespace reference {

using namespace YuvUtils;

struct SeedPoint {
    int x;
    int y;
    int segmentId;
    SeedPoint() : x(0), y(0), segmentId(0) {}
    SeedPoint(int x, int y, int segmentId) : x(x), y(y), segmentId(segmentId) {}
};

std::vector<std::vector<SeedPoint>> generateSeedPoints(int width, int height, int avgSegmentSize, std::mt19937& rng) {
    std::vector<std::vector<SeedPoint>> seedGrid;
    const int gridSpacing = avgSegmentSize;
    const int variation = avgSegmentSize / 3;
    std::uniform_int_distribution<int> variationDist(-variation, variation);
    int segmentId = 0;
    for (int y = gridSpacing / 2; y < height; y += gridSpacing) {
        std::vector<SeedPoint> currentRow;
        for (int x = gridSpacing / 2; x < width; x += gridSpacing) {
            int randomX = std::clamp(x + variationDist(rng), 0, width - 1);
            int randomY = std::clamp(y + variationDist(rng), 0, height - 1);
            currentRow.emplace_back(randomX, randomY, segmentId++);
        }
        if (!currentRow.empty()) seedGrid.push_back(std::move(currentRow));
    }
    return seedGrid;
}

void createSegmentMap(int width, int height, const std::vector<std::vector<SeedPoint>>& seedGrid, int gridSpacing,
                      std::vector<int>& segmentMap, int startY, int endY) {
    for (int y = startY; y < endY; y++) {
        const int cellY = y / gridSpacing;
        const int minCellY = std::max(0, cellY - 1);
        const int maxCellY = std::min(static_cast<int>(seedGrid.size()) - 1, cellY + 1);
        for (int x = 0; x < width; x++) {
            const int cellX = x / gridSpacing;
            const int minCellX = std::max(0, cellX - 1);
            int minDistanceSquared = INT_MAX;
            int nearestSegment = 0;
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                const auto& row = seedGrid[cy];
                const int maxCellX = std::min(static_cast<int>(row.size()) - 1, cellX + 1);
                for (int cx = minCellX; cx <= maxCellX; cx++) {
                    const SeedPoint& point = row[cx];
                    const int dx = x - point.x;
                    const int dy = y - point.y;
                    const int distanceSquared = dx * dx + dy * dy;
                    if (distanceSquared < minDistanceSquared) {
                        minDistanceSquared = distanceSquared;
                        nearestSegment = point.segmentId;
                    }
                }
            }
            segmentMap[y * width + x] = nearestSegment;
        }
    }
}

void calculateSegmentColors(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int width, int height,
                            const std::vector<int>& segmentMap, std::vector<uint64_t>& redTotals,
                            std::vector<uint64_t>& greenTotals, std::vector<uint64_t>& blueTotals,
                            std::vector<uint32_t>& pixelCounts, size_t totalSegments) {
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            const int pixelIndex = y * width + x;
            const int segmentId = segmentMap[pixelIndex];
            const int yVal = yData[pixelIndex];
            const int uVal = uData[(y / 2) * (width / 2) + (x / 2)];
            const int vVal = vData[(y / 2) * (width / 2) + (x / 2)];
            const uint32_t rgb = yuvToRgbFixed(yVal, uVal, vVal, false);
            if (segmentId < (int)totalSegments) {
                redTotals[segmentId] += (rgb >> 16) & 0xFF;
                greenTotals[segmentId] += (rgb >> 8) & 0xFF;
                blueTotals[segmentId] += rgb & 0xFF;
                pixelCounts[segmentId]++;
            }
        }
    }
}

void renderStainedGlass(int width, int height, const std::vector<int>& segmentMap, const std::vector<uint32_t>& segmentColors,
                        uint32_t* outputPixels, int edgeThickness, uint32_t edgeColor, int startY, int endY) {
    for (int y = startY; y < endY; y++) {
        for (int x = 0; x < width; x++) {
            const int pixelIndex = y * width + x;
            const int segmentId = segmentMap[pixelIndex];
            if (segmentId < (int)segmentColors.size()) {
                outputPixels[pixelIndex] = segmentColors[segmentId] | 0xFF000000;
            } else {
                outputPixels[pixelIndex] = 0xFF000000;
            }
        }
    }
    if (edgeThickness > 0) {
        for (int y = startY; y < endY - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                const int currentSegment = segmentMap[y * width + x];
                if (segmentMap[y * width + (x + 1)] != currentSegment) {
                    for (int t = 0; t < edgeThickness && (x + t) < width; t++) {
                        outputPixels[y * width + (x + t)] = edgeColor | 0xFF000000;
                    }
                }
                if (segmentMap[(y + 1) * width + x] != currentSegment) {
                    for (int t = 0; t < edgeThickness && (y + t) < height; t++) {
                        outputPixels[(y + t) * width + x] = edgeColor | 0xFF000000;
                    }
                }
            }
        }
    }
}

// The body of the original JNI function, without the cache.
bool process(const uint8_t* yBytes, const uint8_t* uBytes, const uint8_t* vBytes, int width, int height,
             int segmentSize, int edgeThickness, uint32_t edgeColor, float colorVariation, uint32_t* outputBuffer) {
    std::mt19937 rng(width * height + segmentSize);
    auto seedGrid = generateSeedPoints(width, height, segmentSize, rng);
    std::vector<int> segmentMap(width * height);
    createSegmentMap(width, height, seedGrid, segmentSize, segmentMap, 0, height);
    if (seedGrid.empty()) return false;
    size_t totalSegments = 0;
    for (const auto& row : seedGrid) totalSegments += row.size();
    std::vector<uint64_t> redTotals(totalSegments, 0), greenTotals(totalSegments, 0), blueTotals(totalSegments, 0);
    std::vector<uint32_t> pixelCounts(totalSegments, 0);
    calculateSegmentColors(yBytes, uBytes, vBytes, width, height, segmentMap, redTotals, greenTotals, blueTotals,
                           pixelCounts, totalSegments);
    std::vector<uint32_t> segmentColors(totalSegments);
    std::mt19937 colorRng(width * height + segmentSize + 42);
    std::uniform_int_distribution<int> variationDist(-static_cast<int>(colorVariation * 128),
                                                    static_cast<int>(colorVariation * 128));
    for (int i = 0; i < (int)totalSegments; i++) {
        uint32_t baseColor = 0x000000;
        if (pixelCounts[i] > 0) {
            uint32_t avgRed = static_cast<uint32_t>(redTotals[i] / pixelCounts[i]);
            uint32_t avgGreen = static_cast<uint32_t>(greenTotals[i] / pixelCounts[i]);
            uint32_t avgBlue = static_cast<uint32_t>(blueTotals[i] / pixelCounts[i]);
            baseColor = (avgRed << 16) | (avgGreen << 8) | avgBlue;
        }
        if (colorVariation > 0.0f && pixelCounts[i] > 0) {
            int r = std::clamp(static_cast<int>((baseColor >> 16) & 0xFF) + variationDist(colorRng), 0, 255);
            int g = std::clamp(static_cast<int>((baseColor >> 8) & 0xFF) + variationDist(colorRng), 0, 255);
            int b = std::clamp(static_cast<int>(baseColor & 0xFF) + variationDist(colorRng), 0, 255);
            segmentColors[i] = (r << 16) | (g << 8) | b;
        } else {
            segmentColors[i] = baseColor;
        }
    }
    renderStainedGlass(width, height, segmentMap, segmentColors, outputBuffer, edgeThickness, edgeColor, 0, height);
    return true;
}

}  // namespace reference

int main() {
    std::mt19937 rng(99);
    int failures = 0, cases = 0;
    struct Size { int w, h, stride; };
    const Size sizes[] = {{2, 2, 2}, {9, 7, 12}, {40, 33, 40}, {64, 64, 70}, {131, 101, 131}, {200, 150, 256}};
    const int segmentSizes[] = {4, 11, 16, 40};
    const int thicknesses[] = {0, 1, 2, 3};
    const float variations[] = {0.0f, 0.2f};
    const int threadCounts[] = {1, 2, 5};
    const uint32_t edgeColor = 0xFF102030;
    for (const Size& s : sizes) {
        const int uvSize = ((s.w + 1) / 2) * ((s.h + 1) / 2);
        std::vector<uint8_t> y(s.w * s.h), u(uvSize), v(uvSize);
        for (auto& p : y) p = rng() & 0xFF;
        for (auto& p : u) p = rng() & 0xFF;
        for (auto& p : v) p = rng() & 0xFF;
        for (int seg : segmentSizes) {
            for (int thick : thicknesses) {
                for (float var : variations) {
                    std::vector<uint32_t> expected(s.w * s.h, 0);
                    const bool refOk = reference::process(y.data(), u.data(), v.data(), s.w, s.h, seg, thick, edgeColor, var, expected.data());
                    for (int threads : threadCounts) {
                        std::vector<uint32_t> actual(s.stride * s.h, 0x12345678u);
                        const bool ok = stained_glass::processImage(y.data(), u.data(), v.data(), s.w, s.h, seg, thick,
                                                                    edgeColor, var, actual.data(), s.stride, threads);
                        cases++;
                        if (ok != refOk) {
                            std::printf("FAIL: %dx%d seg=%d: success %d vs reference %d\n", s.w, s.h, seg, ok, refOk);
                            failures++;
                            continue;
                        }
                        if (!ok) continue;
                        bool match = true;
                        for (int row = 0; row < s.h && match; row++) {
                            for (int x = 0; x < s.stride; x++) {
                                uint32_t want = x < s.w ? vc::argbToRgba(expected[row * s.w + x]) : 0x12345678u;
                                if (actual[row * s.stride + x] != want) {
                                    std::printf("FAIL: %dx%d seg=%d thick=%d var=%.1f threads=%d at (%d,%d): got %08x want %08x\n",
                                                s.w, s.h, seg, thick, var, threads, x, row, actual[row * s.stride + x], want);
                                    match = false;
                                    break;
                                }
                            }
                        }
                        if (!match) failures++;
                    }
                }
            }
        }
    }
    if (failures == 0) std::printf("All %d stained glass cases match the reference\n", cases);
    return failures == 0 ? 0 : 1;
}
