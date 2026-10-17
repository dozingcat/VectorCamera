// Host test for contour_effect_native.cpp: checks the threaded native pipeline against a direct
// single-threaded port of the Kotlin ContourEffect algorithm on random images.
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -DCONTOUR_HOST_TEST app/src/test/cpp/contour_effect_native_test.cpp \
//       -o /tmp/contour_test && /tmp/contour_test

#include "../../main/cpp/contour_effect_native.cpp"  // Pulls in worker_pool.h from the same directory.

#include <cstdio>
#include <cstdlib>
#include <random>

namespace {

// Straightforward port of ContourEffect.boxBlur, computeLevels and renderLevels from Kotlin.
void referenceContour(const std::vector<uint8_t>& y, int width, int height, int levels, int radius,
                      int thickness, const std::vector<int32_t>& fill, int32_t lineColor,
                      std::vector<int32_t>& pixels) {
    auto clampI = [](int v, int lo, int hi) { return std::max(lo, std::min(v, hi)); };
    std::vector<uint8_t> smoothed = y;
    if (radius > 0) {
        int kernelSize = 2 * radius + 1;
        std::vector<uint8_t> temp(width * height);
        for (int row = 0; row < height; row++) {
            for (int x = 0; x < width; x++) {
                int total = 0;
                for (int k = -radius; k <= radius; k++) {
                    total += y[row * width + clampI(x + k, 0, width - 1)];
                }
                temp[row * width + x] = static_cast<uint8_t>(total / kernelSize);
            }
        }
        for (int row = 0; row < height; row++) {
            for (int x = 0; x < width; x++) {
                int total = 0;
                for (int k = -radius; k <= radius; k++) {
                    total += temp[clampI(row + k, 0, height - 1) * width + x];
                }
                smoothed[row * width + x] = static_cast<uint8_t>(total / kernelSize);
            }
        }
    }
    std::vector<uint8_t> level(width * height);
    for (int i = 0; i < width * height; i++) {
        level[i] = static_cast<uint8_t>(smoothed[i] * levels / 256);
    }
    for (int row = 0; row < height; row++) {
        for (int x = 0; x < width; x++) {
            int i = row * width + x;
            uint8_t l = level[i];
            bool isLine = (x > 0 && level[i - 1] != l) || (row > 0 && level[i - width] != l);
            if (!isLine && thickness > 1) {
                isLine = (x < width - 1 && level[i + 1] != l) ||
                         (row < height - 1 && level[i + width] != l);
            }
            pixels[i] = isLine ? lineColor : fill[l];
        }
    }
}

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        failures++;
        std::printf("FAIL: %s\n", what);
    }
}

}  // namespace

int main() {
    std::mt19937 rng(12345);
    struct Case { int width, height, levels, radius, thickness, threads; };
    const Case cases[] = {
        {1, 1, 8, 2, 2, 1},       {7, 5, 8, 2, 2, 1},       {64, 48, 8, 2, 2, 1},
        {64, 48, 8, 2, 2, 3},     {160, 97, 12, 0, 1, 4},   {160, 97, 12, 3, 2, 4},
        {33, 200, 2, 1, 1, 7},    {320, 240, 256, 2, 2, 2}, {320, 240, 5, 5, 2, 8},
        // Large radii check the exact-division reciprocal up to the supported limit.
        {90, 70, 8, 7, 2, 2},     {90, 70, 8, 40, 2, 3},    {300, 20, 256, 127, 1, 2},
    };
    for (const Case& c : cases) {
        std::vector<uint8_t> y(c.width * c.height);
        // Mix smooth gradients with noise so there are both large regions and speckles.
        for (int row = 0; row < c.height; row++) {
            for (int x = 0; x < c.width; x++) {
                int v = (x * 255 / std::max(1, c.width - 1) + row * 3 + (rng() % 40)) & 0xFF;
                y[row * c.width + x] = static_cast<uint8_t>(v);
            }
        }
        std::vector<int32_t> fill(c.levels);
        for (int i = 0; i < c.levels; i++) fill[i] = 0xFF000000 | (i * 0x010203);
        const int32_t lineColor = 0xFF123456;

        std::vector<int32_t> expected(c.width * c.height), actual(c.width * c.height, 0);
        referenceContour(y, c.width, c.height, c.levels, c.radius, c.thickness, fill, lineColor, expected);
        contour::processImage(y.data(), c.width, c.height, c.levels, c.radius, c.thickness,
                              fill.data(), lineColor, actual.data(), c.width, c.threads);
        char what[128];
        std::snprintf(what, sizeof(what), "%dx%d levels=%d radius=%d thickness=%d threads=%d",
                      c.width, c.height, c.levels, c.radius, c.thickness, c.threads);
        check(expected == actual, what);
        if (expected != actual) {
            int mismatches = 0;
            for (size_t i = 0; i < expected.size(); i++) mismatches += expected[i] != actual[i];
            std::printf("  %d of %zu pixels differ\n", mismatches, expected.size());
        }
    }
    // Output with a row stride wider than the image, as bitmap buffers can have.
    {
        const int w = 50, h = 40, stride = 57;
        std::vector<uint8_t> y(w * h);
        for (int i = 0; i < w * h; i++) y[i] = static_cast<uint8_t>((i * 13) & 0xFF);
        std::vector<int32_t> fill = {1, 2, 3, 4, 5, 6, 7, 8};
        std::vector<int32_t> expected(w * h), strided(stride * h, -1);
        referenceContour(y, w, h, 8, 2, 2, fill, 99, expected);
        contour::processImage(y.data(), w, h, 8, 2, 2, fill.data(), 99, strided.data(), stride, 3);
        bool ok = true;
        for (int row = 0; row < h && ok; row++) {
            for (int x = 0; x < stride; x++) {
                int32_t want = x < w ? expected[row * w + x] : -1;
                if (strided[row * stride + x] != want) { ok = false; break; }
            }
        }
        check(ok, "strided output");
    }
    if (failures == 0) {
        std::printf("All %zu cases passed\n", sizeof(cases) / sizeof(cases[0]) + 1);
    }
    return failures == 0 ? 0 : 1;
}
