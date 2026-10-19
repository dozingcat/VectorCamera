// Host test for stipple_effect.h: checks the banded, multi-threaded, bitmap-writing renderer
// against a verbatim copy of the branch's original single-threaded renderer (commit 835e10b,
// ARGB output), on random images and point sets, for every combination of parameters below.
//
// Run from the repository root with:
//   clang++ -std=c++17 -O2 -Wall -DVC_NO_JNI app/src/test/cpp/stipple_test.cpp \
//       -o $TMPDIR/stipple_test && $TMPDIR/stipple_test

#include "../../main/cpp/stipple_effect_native.cpp"

#include <cstdio>
#include <random>
#include <string>

namespace reference {

using Stipple::DotMask;
using Stipple::Point;
using Stipple::RenderParams;

struct ToneMap {
    int cellSize = 1;
    int mapWidth = 0;
    int mapHeight = 0;
    std::vector<float> yAvg;
    std::vector<float> uAvg;
    std::vector<float> vAvg;

    void build(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
               int width, int height, int cell, bool includeChroma) {
        cellSize = std::max(1, cell);
        mapWidth = (width + cellSize - 1) / cellSize;
        mapHeight = (height + cellSize - 1) / cellSize;
        const size_t n = static_cast<size_t>(mapWidth) * mapHeight;
        yAvg.assign(n, 0.0f);
        if (includeChroma) {
            uAvg.assign(n, 128.0f);
            vAvg.assign(n, 128.0f);
        }
        const int uvWidth = (width + 1) / 2;
        for (int my = 0; my < mapHeight; my++) {
            const int y0 = my * cellSize;
            const int y1 = std::min(height, y0 + cellSize);
            for (int mx = 0; mx < mapWidth; mx++) {
                const int x0 = mx * cellSize;
                const int x1 = std::min(width, x0 + cellSize);
                uint32_t ySum = 0;
                for (int y = y0; y < y1; y++) {
                    const uint8_t* row = yData + static_cast<size_t>(y) * width;
                    for (int x = x0; x < x1; x++) {
                        ySum += row[x];
                    }
                }
                const int count = std::max(1, (y1 - y0) * (x1 - x0));
                const size_t mi = static_cast<size_t>(my) * mapWidth + mx;
                yAvg[mi] = static_cast<float>(ySum) / count;
                if (includeChroma) {
                    // Chroma is subsampled 2x2, so step by 2 pixels.
                    uint32_t uSum = 0, vSum = 0, uvCount = 0;
                    for (int y = y0; y < y1; y += 2) {
                        const size_t uvRow = static_cast<size_t>(y / 2) * uvWidth;
                        for (int x = x0; x < x1; x += 2) {
                            uSum += uData[uvRow + x / 2];
                            vSum += vData[uvRow + x / 2];
                            uvCount++;
                        }
                    }
                    if (uvCount > 0) {
                        uAvg[mi] = static_cast<float>(uSum) / uvCount;
                        vAvg[mi] = static_cast<float>(vSum) / uvCount;
                    }
                }
            }
        }
    }

    // Bilinearly samples one of the channel arrays at image coordinates (x, y).
    float sample(const std::vector<float>& channel, float x, float y) const {
        float fx = x / cellSize - 0.5f;
        float fy = y / cellSize - 0.5f;
        fx = std::clamp(fx, 0.0f, static_cast<float>(mapWidth - 1));
        fy = std::clamp(fy, 0.0f, static_cast<float>(mapHeight - 1));
        const int ix = std::min(mapWidth - 1, static_cast<int>(fx));
        const int iy = std::min(mapHeight - 1, static_cast<int>(fy));
        const int ix1 = std::min(mapWidth - 1, ix + 1);
        const int iy1 = std::min(mapHeight - 1, iy + 1);
        const float tx = fx - ix;
        const float ty = fy - iy;
        const float top = channel[static_cast<size_t>(iy) * mapWidth + ix] * (1 - tx) +
                          channel[static_cast<size_t>(iy) * mapWidth + ix1] * tx;
        const float bottom = channel[static_cast<size_t>(iy1) * mapWidth + ix] * (1 - tx) +
                             channel[static_cast<size_t>(iy1) * mapWidth + ix1] * tx;
        return top * (1 - ty) + bottom * ty;
    }
};

inline void blendDot(uint32_t* out, int width, int height, int cx, int cy,
                     const DotMask& mask, uint32_t color) {
    const int e = mask.extent;
    const int size = 2 * e + 1;
    const int y0 = std::max(0, cy - e), y1 = std::min(height - 1, cy + e);
    const int x0 = std::max(0, cx - e), x1 = std::min(width - 1, cx + e);
    const uint32_t cr = (color >> 16) & 0xFF;
    const uint32_t cg = (color >> 8) & 0xFF;
    const uint32_t cb = color & 0xFF;
    for (int y = y0; y <= y1; y++) {
        const uint8_t* maskRow = &mask.alpha[static_cast<size_t>(y - cy + e) * size];
        uint32_t* outRow = out + static_cast<size_t>(y) * width;
        for (int x = x0; x <= x1; x++) {
            const uint32_t a = maskRow[x - cx + e];
            if (a == 0) {
                continue;
            }
            if (a == 255) {
                outRow[x] = 0xFF000000u | (cr << 16) | (cg << 8) | cb;
                continue;
            }
            const uint32_t dst = outRow[x];
            const uint32_t ia = 255 - a;
            const uint32_t r = (((dst >> 16) & 0xFF) * ia + cr * a + 127) / 255;
            const uint32_t g = (((dst >> 8) & 0xFF) * ia + cg * a + 127) / 255;
            const uint32_t b = ((dst & 0xFF) * ia + cb * a + 127) / 255;
            outRow[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
        }
    }
}

/**
 * Renders the stipple image into `out` (width*height ARGB pixels).
 */
inline void renderStipple(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
                          int width, int height,
                          const Point* points, int numPoints,
                          const RenderParams& params, uint32_t* out) {
    const size_t numPixels = static_cast<size_t>(width) * height;
    std::fill(out, out + numPixels, params.backgroundColor | 0xFF000000u);

    ToneMap tone;
    tone.build(yData, uData, vData, width, height, params.toneCellSize, params.colorFromImage);

    // Dot masks are cached per quantized radius (quarter-pixel steps).
    std::vector<DotMask> masks;
    const float radiusStep = 0.25f;
    const float minRadius = 0.5f;
    const bool applyGamma = std::fabs(params.gamma - 1.0f) > 1e-4f;

    for (int i = 0; i < numPoints; i++) {
        const Point& p = points[i];
        const float yValue = tone.sample(tone.yAvg, p.x, p.y);
        float darkness = params.invertTone ? yValue / 255.0f : 1.0f - yValue / 255.0f;
        darkness = std::clamp(darkness, 0.0f, 1.0f);
        if (applyGamma) {
            darkness = std::pow(darkness, params.gamma);
        }
        if (p.rank > darkness) {
            continue;
        }
        const float radius = std::max(minRadius,
                params.dotRadius * (1.0f + params.dotSizeVariation * (darkness - 0.5f)));
        const int radiusIndex = static_cast<int>(radius / radiusStep + 0.5f);
        if (radiusIndex >= static_cast<int>(masks.size())) {
            masks.resize(radiusIndex + 1);
        }
        DotMask& mask = masks[radiusIndex];
        if (mask.alpha.empty()) {
            mask.build(radiusIndex * radiusStep);
        }

        uint32_t color = params.dotColor;
        if (params.colorFromImage) {
            const float u = tone.sample(tone.uAvg, p.x, p.y);
            const float v = tone.sample(tone.vAvg, p.x, p.y);
            const int dy = static_cast<int>(yValue * params.dotLuminanceScale + 0.5f);
            const int du = static_cast<int>(128.0f + (u - 128.0f) * params.chromaBoost + 0.5f);
            const int dv = static_cast<int>(128.0f + (v - 128.0f) * params.chromaBoost + 0.5f);
            color = YuvUtils::yuvToRgb(std::clamp(dy, 0, 255), std::clamp(du, 0, 255),
                                       std::clamp(dv, 0, 255), true);
        }
        blendDot(out, width, height, static_cast<int>(p.x + 0.5f), static_cast<int>(p.y + 0.5f),
                 mask, color);
    }
}
}  // namespace reference

int main() {
    std::mt19937 rng(777);
    int failures = 0, cases = 0;
    struct Size { int w, h, stride; };
    const Size sizes[] = {{1, 1, 1}, {7, 5, 7}, {64, 48, 64}, {200, 150, 208}, {333, 211, 340}};
    struct Style { const char* name; uint32_t bg, dot; bool fromImage, invert; };
    const Style styles[] = {
        {"black on white", 0xFFFFFFFFu, 0xFF000000u, false, false},
        {"white on black", 0xFF000000u, 0xFFFFFFFFu, false, true},
        {"color", 0xFFF0E8D0u, 0xFF000000u, true, false},
    };
    const float spacings[] = {3.0f, 4.5f, 8.0f};
    const float variations[] = {0.0f, 0.5f, 1.0f};
    const float gammas[] = {1.0f, 0.7f};
    const int threadCounts[] = {1, 2, 5};
    for (const Size& s : sizes) {
        const int uvW = (s.w + 1) / 2, uvH = (s.h + 1) / 2;
        std::vector<uint8_t> y(s.w * s.h), u(uvW * uvH), v(uvW * uvH);
        // A gradient with noise, so the tone covers the whole range.
        for (int row = 0; row < s.h; row++)
            for (int x = 0; x < s.w; x++)
                y[row * s.w + x] = static_cast<uint8_t>(std::min(255, (x + row) * 255 / std::max(1, s.w + s.h - 2) + static_cast<int>(rng() % 40)));
        for (auto& p : u) p = 60 + rng() % 140;
        for (auto& p : v) p = 60 + rng() % 140;
        for (float spacing : spacings) {
            const std::vector<Stipple::Point> points =
                    Stipple::generateProgressivePoissonPoints(s.w, s.h, spacing, 6, 3);
            for (const Style& style : styles) {
                for (float variation : variations) {
                    for (float gamma : gammas) {
                        Stipple::RenderParams params;
                        params.dotRadius = spacing * 0.65f;
                        params.dotSizeVariation = variation;
                        params.gamma = gamma;
                        params.toneCellSize = std::max(1, static_cast<int>(spacing + 0.5f));
                        params.backgroundColor = style.bg;
                        params.dotColor = style.dot;
                        params.colorFromImage = style.fromImage;
                        params.invertTone = style.invert;
                        std::vector<uint32_t> expectedArgb(s.w * s.h);
                        reference::renderStipple(y.data(), u.data(), v.data(), s.w, s.h, points.data(),
                                                 static_cast<int>(points.size()), params, expectedArgb.data());
                        for (int threads : threadCounts) {
                            std::vector<uint32_t> actual(s.stride * s.h, 0x12345678u);
                            Stipple::renderStipple(y.data(), u.data(), v.data(), s.w, s.h, points.data(),
                                                   static_cast<int>(points.size()), params, actual.data(),
                                                   s.stride, threads);
                            cases++;
                            bool ok = true;
                            for (int row = 0; row < s.h && ok; row++) {
                                for (int x = 0; x < s.stride; x++) {
                                    const uint32_t want = x < s.w ? vc::argbToRgba(expectedArgb[row * s.w + x]) : 0x12345678u;
                                    if (actual[row * s.stride + x] != want) {
                                        std::printf("FAIL: %dx%d %s spacing=%.1f var=%.1f gamma=%.1f threads=%d at (%d,%d): got %08x want %08x\n",
                                                    s.w, s.h, style.name, spacing, variation, gamma, threads, x, row,
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
    }
    if (failures == 0) std::printf("All %d stipple cases match the reference\n", cases);
    return failures == 0 ? 0 : 1;
}
