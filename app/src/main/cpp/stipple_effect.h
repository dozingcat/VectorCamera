#ifndef STIPPLE_EFFECT_H
#define STIPPLE_EFFECT_H

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "yuv.h"

/**
 * Stippling effect: the image is rendered as dots placed by Poisson disk sampling, where the
 * local density of dots follows the local tone of the image (dark areas get more dots).
 *
 * This header contains the pure C++ implementation so it can be compiled and tested outside
 * of Android; the JNI bindings are in stipple_effect_native.cpp.
 *
 * Dot placement: rather than running an adaptive Poisson disk sampler every frame (which is
 * slow and flickers because a small change in the image changes every sample), we generate a
 * "progressive" Poisson disk point set once per image size. Bridson's algorithm is run in
 * several layers, each with a minimum spacing 1/sqrt(2) times the previous one (so the point
 * density doubles per layer), and each layer keeps all the points of the previous layers and
 * fills in the gaps. Every point is assigned a rank in (0, 1] such that the subset of points
 * with rank <= t has density approximately t times the maximum density, and is itself
 * approximately Poisson disk distributed. Rendering a frame is then just: for each point, look
 * up the local darkness d of the image and draw the dot if rank <= d.
 */
namespace Stipple {

struct Point {
    float x;
    float y;
    float rank;
};

/**
 * Small fast deterministic PRNG (xorshift32). Point generation is dominated by random number
 * generation, and std::mt19937 with distributions is several times slower.
 */
struct FastRandom {
    uint32_t state;
    explicit FastRandom(uint32_t seed) : state(seed * 2654435761u + 0x9E3779B9u) {
        if (state == 0) state = 0x9E3779B9u;
    }
    uint32_t nextUInt() {
        uint32_t x = state;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        state = x;
        return x;
    }
    // Uniform in [0, 1).
    float nextFloat() {
        return static_cast<float>(nextUInt() >> 8) * (1.0f / 16777216.0f);
    }
};

/**
 * Generates a progressive Poisson disk point set covering [0, width) x [0, height).
 * The finest layer has minimum spacing `minSpacing`; there are `numLayers` layers in total.
 * The result is deterministic for a given seed.
 */
inline std::vector<Point> generateProgressivePoissonPoints(
        int width, int height, float minSpacing, int numLayers, uint32_t seed) {
    std::vector<Point> points;
    if (width <= 0 || height <= 0 || minSpacing <= 0.0f || numLayers <= 0) {
        return points;
    }
    FastRandom rng(seed);
    auto unit = [&](FastRandom& r) { return r.nextFloat(); };
    const int candidatesPerPoint = 12;
    const float twoPi = 6.283185307f;
    const float fw = static_cast<float>(width);
    const float fh = static_cast<float>(height);

    for (int layer = 0; layer < numLayers; layer++) {
        const float r = minSpacing * std::pow(2.0f, (numLayers - 1 - layer) * 0.5f);
        const float r2 = r * r;
        // With this cell size each cell can contain at most one point.
        const float cellSize = r / 1.41421356f;
        const int cols = std::max(1, static_cast<int>(std::ceil(fw / cellSize)));
        const int rows = std::max(1, static_cast<int>(std::ceil(fh / cellSize)));
        std::vector<int> grid(static_cast<size_t>(cols) * rows, -1);

        auto cellX = [&](float x) { return std::min(cols - 1, static_cast<int>(x / cellSize)); };
        auto cellY = [&](float y) { return std::min(rows - 1, static_cast<int>(y / cellSize)); };

        auto isFarFromExisting = [&](float x, float y) {
            const int cx = cellX(x);
            const int cy = cellY(y);
            // Any point in the same cell is closer than r (the cell diagonal), so reject
            // immediately; this is the common case once the layer is nearly full.
            if (grid[static_cast<size_t>(cy) * cols + cx] >= 0) {
                return false;
            }
            const int gy0 = std::max(0, cy - 2), gy1 = std::min(rows - 1, cy + 2);
            const int gx0 = std::max(0, cx - 2), gx1 = std::min(cols - 1, cx + 2);
            for (int gy = gy0; gy <= gy1; gy++) {
                const int* row = &grid[static_cast<size_t>(gy) * cols];
                const bool edgeRow = (gy == cy - 2 || gy == cy + 2);
                for (int gx = gx0; gx <= gx1; gx++) {
                    // The corner cells of the 5x5 neighborhood are at least r away.
                    if (edgeRow && (gx == cx - 2 || gx == cx + 2)) {
                        continue;
                    }
                    const int idx = row[gx];
                    if (idx >= 0) {
                        const float dx = points[idx].x - x;
                        const float dy = points[idx].y - y;
                        if (dx * dx + dy * dy < r2) {
                            return false;
                        }
                    }
                }
            }
            return true;
        };

        // Points added in this layer get ranks in (rankMin, rankMax]. The density of the
        // full layer is 2^(layer - (numLayers-1)) times the maximum density, and half of the
        // layer's points are new, so this keeps density linear in rank.
        const float rankMax = std::pow(2.0f, static_cast<float>(layer - (numLayers - 1)));
        const float rankMin = (layer == 0) ? 0.0f : rankMax * 0.5f;
        auto newRank = [&]() { return rankMin + unit(rng) * (rankMax - rankMin); };

        std::vector<int> active;
        active.reserve(points.size() * 2 + 16);
        for (int i = 0; i < static_cast<int>(points.size()); i++) {
            grid[static_cast<size_t>(cellY(points[i].y)) * cols + cellX(points[i].x)] = i;
            active.push_back(i);
        }
        if (points.empty()) {
            Point p{unit(rng) * fw, unit(rng) * fh, newRank()};
            points.push_back(p);
            grid[static_cast<size_t>(cellY(p.y)) * cols + cellX(p.x)] = 0;
            active.push_back(0);
        }

        while (!active.empty()) {
            const int ai = static_cast<int>(rng.nextUInt() % active.size());
            // Copy, since `points` may reallocate below.
            const Point base = points[active[ai]];
            bool found = false;
            // Candidates are placed just outside the minimum distance at evenly spaced angles
            // with a random rotation. This packs more tightly and needs far fewer candidates
            // than the uniform annulus sampling in Bridson's original algorithm.
            const float angleOffset = unit(rng);
            for (int k = 0; k < candidatesPerPoint; k++) {
                const float angle = (k + angleOffset) * (twoPi / candidatesPerPoint);
                const float dist = r * (1.0f + 0.15f * unit(rng));
                const float x = base.x + dist * std::cos(angle);
                const float y = base.y + dist * std::sin(angle);
                if (x < 0.0f || x >= fw || y < 0.0f || y >= fh) {
                    continue;
                }
                if (!isFarFromExisting(x, y)) {
                    continue;
                }
                const int idx = static_cast<int>(points.size());
                points.push_back(Point{x, y, newRank()});
                grid[static_cast<size_t>(cellY(y)) * cols + cellX(x)] = idx;
                active.push_back(idx);
                found = true;
                break;
            }
            if (!found) {
                active[ai] = active.back();
                active.pop_back();
            }
        }
    }
    return points;
}

struct RenderParams {
    // Base dot radius in pixels.
    float dotRadius = 3.0f;
    // 0 = all dots the same size. 1 = radius ranges from 0.5x (lightest) to 1.5x (darkest).
    float dotSizeVariation = 0.5f;
    // Exponent applied to darkness before comparing with point ranks. <1 makes the image darker.
    float gamma = 1.0f;
    // Size in pixels of the blocks over which tone is averaged.
    int toneCellSize = 4;
    // ARGB colors.
    uint32_t backgroundColor = 0xFFFFFFFF;
    uint32_t dotColor = 0xFF000000;
    // If true dots take their color from the image instead of dotColor.
    bool colorFromImage = false;
    // In colorFromImage mode, the luminance of the image color is multiplied by this and the
    // chroma by chromaBoost, so that dots stay visible against a light background.
    float dotLuminanceScale = 0.5f;
    float chromaBoost = 1.5f;
    // If true, dots are dense where the image is bright rather than dark (for dark backgrounds).
    bool invertTone = false;
};

/**
 * Anti-aliased circular dot mask with 0-255 coverage values.
 */
struct DotMask {
    int extent = 0;  // Mask covers [-extent, extent] in both directions.
    std::vector<uint8_t> alpha;

    void build(float radius) {
        extent = std::max(0, static_cast<int>(std::ceil(radius + 0.5f)));
        const int size = 2 * extent + 1;
        alpha.assign(static_cast<size_t>(size) * size, 0);
        for (int dy = -extent; dy <= extent; dy++) {
            for (int dx = -extent; dx <= extent; dx++) {
                const float dist = std::sqrt(static_cast<float>(dx * dx + dy * dy));
                const float coverage = std::clamp(radius + 0.5f - dist, 0.0f, 1.0f);
                alpha[static_cast<size_t>(dy + extent) * size + (dx + extent)] =
                        static_cast<uint8_t>(coverage * 255.0f + 0.5f);
            }
        }
    }
};

/**
 * Tone map: image luminance (and chroma) averaged over square cells, sampled bilinearly.
 */
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

}  // namespace Stipple

#endif  // STIPPLE_EFFECT_H
