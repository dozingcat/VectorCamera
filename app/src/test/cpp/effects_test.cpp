// Host test for the native effects: each rewritten kernel is checked against a straight copy of
// the kernel it replaced, on random images, across thread counts and output strides. The
// references write ARGB color ints as the originals did; the new kernels write bitmap pixels,
// so the reference output is converted before comparing.
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -DVC_NO_JNI app/src/test/cpp/effects_test.cpp -o /tmp/effects_test \
//       && /tmp/effects_test

#include "../../main/cpp/color_map_effect_native.cpp"
#include "../../main/cpp/edge_luminance_native.cpp"
#include "../../main/cpp/permute_color_effect_native.cpp"

#include <cstdio>
#include <random>
#include <string>
#include <vector>

namespace reference {

// --- color_map_effect_native.cpp as of commit e688b42 ---
inline int clamp(int value, int min_val, int max_val) {
    return std::max(min_val, std::min(value, max_val));
}
inline int calculateEdgeStrength(const uint8_t* yData, int x, int y, int width, int height,
                                 int yRowStride, int multiplier) {
    if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) return 0;
    int center = yData[y * yRowStride + x];
    int surrounding =
        yData[(y - 1) * yRowStride + (x - 1)] + yData[(y - 1) * yRowStride + x] +
        yData[(y - 1) * yRowStride + (x + 1)] + yData[y * yRowStride + (x - 1)] +
        yData[y * yRowStride + (x + 1)] + yData[(y + 1) * yRowStride + (x - 1)] +
        yData[(y + 1) * yRowStride + x] + yData[(y + 1) * yRowStride + (x + 1)];
    int edge = 8 * center - surrounding;
    return clamp(multiplier * edge, 0, 255);
}
void colorMap(const uint8_t* yData, int width, int height, bool edgeMode, int multiplier,
              const int* colorMap, int* out) {
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int index = edgeMode ? calculateEdgeStrength(yData, x, y, width, height, width, multiplier)
                                 : yData[y * width + x];
            out[y * width + x] = colorMap[index];
        }
    }
}

// --- edge_luminance_native.cpp as of commit e688b42 ---
inline int edgeStrength2(const uint8_t* yData, int x, int y, int width, int height, int multiplier) {
    if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) return 0;
    const uint8_t* prevRow = yData + (y - 1) * width;
    const uint8_t* currRow = yData + y * width;
    const uint8_t* nextRow = yData + (y + 1) * width;
    const int center = currRow[x];
    const int surroundingSum = prevRow[x - 1] + prevRow[x] + prevRow[x + 1] +
                               currRow[x - 1] + currRow[x + 1] +
                               nextRow[x - 1] + nextRow[x] + nextRow[x + 1];
    const int result = multiplier * (8 * center - surroundingSum);
    return (result < 0) ? 0 : (result > 255) ? 255 : result;
}
void edgeLuminance(int width, int height, int multiplier, const uint8_t* yPtr, const uint8_t* uPtr,
                   const uint8_t* vPtr, int uvWidth, int* pixelArray) {
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            const int pixelIndex = y * width + x;
            const int edge = edgeStrength2(yPtr, x, y, width, height, multiplier);
            const int uvIndex = (y / 2) * uvWidth + (x / 2);
            pixelArray[pixelIndex] = static_cast<int>(YuvUtils::yuvToRgbFixed(edge, uPtr[uvIndex], vPtr[uvIndex], true));
        }
    }
}

// --- permute_color_effect_native.cpp as of commit e688b42 ---
inline int extractComponent(int r, int g, int b, int y, int source) {
    switch (source) {
        case 1: return r; case 2: return g; case 3: return b; case 4: return y;
        case 5: return 255 - r; case 6: return 255 - g; case 7: return 255 - b; case 8: return 255 - y;
        case 0: return 0; case -1: return 255; default: return 0;
    }
}
void permute(int width, int height, const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
             int* pixels, int redSource, int greenSource, int blueSource, bool flipUV) {
    int uvWidth = (width + 1) / 2;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int pixelIndex = y * width + x;
            int yy = yData[pixelIndex] & 0xFF;
            int uvIndex = (y / 2) * uvWidth + (x / 2);
            int u = uData[uvIndex] & 0xFF, v = vData[uvIndex] & 0xFF;
            int uu = flipUV ? (-u & 0xFF) : u;
            int vv = flipUV ? (-v & 0xFF) : v;
            int rgb = YuvUtils::yuvToRgb(yy, uu, vv, false);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            pixels[pixelIndex] = (255 << 24) | (extractComponent(r, g, b, yy, redSource) << 16) |
                                 (extractComponent(r, g, b, yy, greenSource) << 8) |
                                 extractComponent(r, g, b, yy, blueSource);
        }
    }
}

}  // namespace reference

namespace {

int failures = 0;
std::mt19937 rng(777);

struct Image {
    int width, height, uvWidth;
    std::vector<uint8_t> y, u, v;
    Image(int w, int h) : width(w), height(h), uvWidth((w + 1) / 2),
                          y(w * h), u(uvWidth * ((h + 1) / 2)), v(u.size()) {
        for (auto& p : y) p = rng() & 0xFF;
        for (auto& p : u) p = rng() & 0xFF;
        for (auto& p : v) p = rng() & 0xFF;
    }
};

// Compares `actual` (bitmap pixels, rows `stride` apart, padding untouched at -1) to `expected`
// ARGB ints after conversion.
void check(const std::string& what, const std::vector<int>& expected, const std::vector<uint32_t>& actual,
           int width, int height, int stride) {
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < stride; x++) {
            uint32_t want = x < width ? vc::argbToRgba(static_cast<uint32_t>(expected[y * width + x])) : 0xFFFFFFFFu;
            if (actual[y * stride + x] != want) {
                failures++;
                std::printf("FAIL: %s at (%d,%d): got %08x want %08x\n", what.c_str(), x, y, actual[y * stride + x], want);
                return;
            }
        }
    }
}

}  // namespace

// Translucent palette colors must come out premultiplied, as Bitmap.setPixels would store them.
bool checkPremultiply() {
    struct Case { uint32_t argb, rgba; };
    const Case cases[] = {
        {0xFF102030u, 0xFF302010u},  // Opaque: components reordered only.
        {0x80FF0000u, 0x80000080u},  // 255 * 128 / 255 = 128.
        {0x00FFFFFFu, 0x00000000u},  // Fully transparent.
        {0x7F0000FFu, 0x7F7F0000u},  // 255 * 127 / 255 = 127.
        {0x33C8C8C8u, 0x33282828u},  // round(200 * 51 / 255) = 40.
    };
    bool ok = true;
    for (const Case& c : cases) {
        const uint32_t got = vc::argbToRgba(c.argb);
        if (got != c.rgba) {
            std::printf("FAIL: argbToRgba(%08x) = %08x, want %08x\n", c.argb, got, c.rgba);
            ok = false;
        }
    }
    return ok;
}

int main() {
    if (!checkPremultiply()) return 1;
    struct Size { int w, h, stride; };
    const Size sizes[] = {{1, 1, 1}, {2, 3, 2}, {17, 9, 20}, {64, 70, 64}, {131, 101, 140}};
    const int threadCounts[] = {1, 2, 5};
    for (const Size& s : sizes) {
        Image img(s.w, s.h);
        std::vector<int> colorMap(256);
        for (int i = 0; i < 256; i++) colorMap[i] = static_cast<int>(rng() | 0xFF000000u);
        std::vector<uint32_t> bitmapMap(256);
        for (int i = 0; i < 256; i++) bitmapMap[i] = vc::argbToRgba(colorMap[i]);
        char label[64];
        for (int threads : threadCounts) {
            // Color map, both modes.
            for (int edgeMode = 0; edgeMode < 2; edgeMode++) {
                std::vector<int> expected(s.w * s.h);
                reference::colorMap(img.y.data(), s.w, s.h, edgeMode, 3, colorMap.data(), expected.data());
                std::vector<uint32_t> actual(s.stride * s.h, 0xFFFFFFFFu);
                colormap::processImage(img.y.data(), s.w, s.h, edgeMode, 3, bitmapMap.data(), actual.data(), s.stride, threads);
                std::snprintf(label, sizeof(label), "colormap %dx%d edge=%d threads=%d", s.w, s.h, edgeMode, threads);
                check(label, expected, actual, s.w, s.h, s.stride);
            }
            // Edge luminance.
            {
                std::vector<int> expected(s.w * s.h);
                reference::edgeLuminance(s.w, s.h, 2, img.y.data(), img.u.data(), img.v.data(), img.uvWidth, expected.data());
                std::vector<uint32_t> actual(s.stride * s.h, 0xFFFFFFFFu);
                edge_luminance::processImage(img.y.data(), img.u.data(), img.v.data(), s.w, s.h, img.uvWidth, 2, actual.data(), s.stride, threads);
                std::snprintf(label, sizeof(label), "edge_luminance %dx%d threads=%d", s.w, s.h, threads);
                check(label, expected, actual, s.w, s.h, s.stride);
            }
            // Permute: every source code for each channel, with and without flipUV.
            for (int source = -1; source <= 8; source++) {
                for (int flip = 0; flip < 2; flip++) {
                    const int r = source, g = ((source + 3) % 10) - 1, b = ((source + 6) % 10) - 1;
                    std::vector<int> expected(s.w * s.h);
                    reference::permute(s.w, s.h, img.y.data(), img.u.data(), img.v.data(), expected.data(), r, g, b, flip);
                    std::vector<uint32_t> actual(s.stride * s.h, 0xFFFFFFFFu);
                    permute::processImage(img.y.data(), img.u.data(), img.v.data(), s.w, s.h, r, g, b, flip, actual.data(), s.stride, threads);
                    std::snprintf(label, sizeof(label), "permute %dx%d sources=%d,%d,%d flip=%d threads=%d", s.w, s.h, r, g, b, flip, threads);
                    check(label, expected, actual, s.w, s.h, s.stride);
                }
            }
        }
    }
    if (failures == 0) std::printf("All effect kernels match their references\n");
    return failures == 0 ? 0 : 1;
}
