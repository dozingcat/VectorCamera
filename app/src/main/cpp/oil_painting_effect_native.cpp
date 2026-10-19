// Native implementation of OilPaintingEffect: the camera image is scaled down, converted to
// quantized RGB, and each output pixel takes the most common color within a circular brush around
// it, whose radius shrinks where the local contrast is high. Renders straight into the bitmap.
//
// Pass 1 (parallel over rows) samples the scaled image straight from the full-size planes
// (bilinear, matching the Kotlin resizeImageBytes) and stores, per pixel, a 15-bit hash of the
// quantized color and its brightness.
//
// Pass 2 (parallel over row chunks; rows are independent) computes each pixel's brush radius from
// the brightness variance in its 7x7 neighborhood using sliding window sums, then slides the brush
// along the row. The brush histogram is updated with exactly the pixels that enter or leave the
// disc, per disc row, including when the radius changes between neighboring pixels; the obvious
// implementation rebuilds the whole histogram whenever the radius changes, which on noisy camera
// input is most pixels. When equally common colors tie, the smallest hash wins, so the result
// doesn't depend on the order colors entered the brush.
//
// Compiling with -DVC_NO_JNI leaves out the JNI entry point so the kernels can be unit tested on
// the host (app/src/test/cpp/oil_painting_test.cpp).

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <vector>

#include "pixel_format.h"
#include "worker_pool.h"
#include "yuv.h"

#ifndef VC_NO_JNI
#include <jni.h>
#include <android/log.h>
#include "bitmap_output.h"
#define LOG_TAG "OilPaintingEffectNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

namespace oil {

constexpr int kMaxBrushRadius = 20;
constexpr int kMinBrushRadius = 2;
constexpr int kContrastRadius = 3;
constexpr int kNumHashes = 1 << 15;

// Half width of the brush disc on the row `dy` above or below its center: the disc of radius r
// covers dx in [-halfWidth[r][|dy|], halfWidth[r][|dy|]], or nothing if |dy| > r.
using DiscShape = std::array<int16_t, kMaxBrushRadius + 1>;

const std::array<DiscShape, kMaxBrushRadius + 1>& discShapes() {
    static const std::array<DiscShape, kMaxBrushRadius + 1> shapes = [] {
        std::array<DiscShape, kMaxBrushRadius + 1> result{};
        for (int r = 0; r <= kMaxBrushRadius; r++) {
            for (int dy = 0; dy <= kMaxBrushRadius; dy++) {
                int w = -1;
                if (dy <= r) {
                    w = 0;
                    while ((w + 1) * (w + 1) + dy * dy <= r * r) w++;
                }
                result[r][dy] = static_cast<int16_t>(w);
            }
        }
        return result;
    }();
    return shapes;
}

// Per-channel tables derived from the quantization levels.
struct ColorTables {
    // Quantized value for each channel value.
    uint8_t quantize[256];
    // The hash contribution of each channel value: the top 5 bits of its quantized value.
    uint16_t hashR[256], hashG[256], hashB[256];
    // Brightness contribution of each channel value, in thousandths (0.299, 0.587, 0.114).
    uint32_t brightR[256], brightG[256], brightB[256];
    // The quantized value each 5-bit hash field stands for. With 32 or fewer levels the hash is
    // lossless and this is the only quantized value with that top 5 bits; with more levels it's
    // the smallest one.
    uint8_t representative[32];

    explicit ColorTables(int levels) {
        const int step = std::max(1, 256 / std::max(1, levels));
        bool seen[32] = {};
        for (int i = 0; i < 256; i++) {
            const uint8_t q = static_cast<uint8_t>((i / step) * step);
            quantize[i] = q;
            hashR[i] = static_cast<uint16_t>((q >> 3) << 10);
            hashG[i] = static_cast<uint16_t>((q >> 3) << 5);
            hashB[i] = static_cast<uint16_t>(q >> 3);
            brightR[i] = 299u * q;
            brightG[i] = 587u * q;
            brightB[i] = 114u * q;
            if (!seen[q >> 3]) {
                seen[q >> 3] = true;
                representative[q >> 3] = q;
            }
        }
    }

    uint32_t bitmapColor(uint16_t hash) const {
        return vc::packRgba(representative[hash >> 10], representative[(hash >> 5) & 31],
                            representative[hash & 31]);
    }
};

// Scaled-down image in the form the brush pass reads.
struct ImagePlanes {
    std::vector<uint16_t> hash;
    std::vector<uint32_t> brightness;
};

// One row of bilinear sampling positions, matching the Kotlin resizeImageBytes: `x * ratio`
// in float, truncated, with the fraction as the weight of the next sample.
struct SamplePositions {
    std::vector<int> index0, index1;
    std::vector<float> fraction;

    SamplePositions(int srcSize, int dstSize) : index0(dstSize), index1(dstSize), fraction(dstSize) {
        const float ratio = static_cast<float>(srcSize) / static_cast<float>(dstSize);
        for (int i = 0; i < dstSize; i++) {
            const float p = static_cast<float>(i) * ratio;
            const int i0 = static_cast<int>(p);
            index0[i] = i0;
            index1[i] = std::min(i0 + 1, srcSize - 1);
            fraction[i] = p - static_cast<float>(i0);
        }
    }
};

// Bilinear sample of a plane at the positions (px, py), computed as the Kotlin code does.
inline uint8_t sampleBilinear(const uint8_t* plane, int planeWidth, const SamplePositions& px,
                              const SamplePositions& py, int x, int y) {
    const uint8_t* row0 = plane + static_cast<size_t>(py.index0[y]) * planeWidth;
    const uint8_t* row1 = plane + static_cast<size_t>(py.index1[y]) * planeWidth;
    const float fx = px.fraction[x];
    const float fy = py.fraction[y];
    const float p1 = row0[px.index0[x]];
    const float p2 = row0[px.index1[x]];
    const float p3 = row1[px.index0[x]];
    const float p4 = row1[px.index1[x]];
    const float ifx = 1.0f - fx;
    const float ify = 1.0f - fy;
    // Separate statements keep the compiler from fusing multiplies and adds, so the rounding
    // matches the Kotlin implementation.
    const float t1 = p1 * ifx * ify;
    const float t2 = p2 * fx * ify;
    const float t3 = p3 * ifx * fy;
    const float t4 = p4 * fx * fy;
    const float sum = ((t1 + t2) + t3) + t4;
    return static_cast<uint8_t>(static_cast<int>(sum));
}

// Geometry of the scaled image and its YUV sampling positions.
struct ScaledImage {
    int srcWidth, srcHeight, width, height;
    int srcUvWidth, uvWidth, uvHeight;
    SamplePositions yx, yy, uvx, uvy;

    ScaledImage(int srcWidth, int srcHeight, int width, int height)
            : srcWidth(srcWidth), srcHeight(srcHeight), width(width), height(height),
              srcUvWidth((srcWidth + 1) / 2), uvWidth((width + 1) / 2), uvHeight((height + 1) / 2),
              yx(srcWidth, width), yy(srcHeight, height),
              uvx(srcUvWidth, uvWidth), uvy((srcHeight + 1) / 2, uvHeight) {}
};

// Fills rows [startRow, endRow) of `planes` from the full-size YUV planes.
void convertRows(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
                 const ScaledImage& image, const ColorTables& tables, ImagePlanes& planes,
                 int startRow, int endRow) {
    const int width = image.width;
    for (int y = startRow; y < endRow; y++) {
        uint16_t* hashRow = planes.hash.data() + static_cast<size_t>(y) * width;
        uint32_t* brightRow = planes.brightness.data() + static_cast<size_t>(y) * width;
        const int uvY = y / 2;
        int u = 0, v = 0;
        for (int x = 0; x < width; x++) {
            if ((x & 1) == 0) {
                u = sampleBilinear(uData, image.srcUvWidth, image.uvx, image.uvy, x / 2, uvY);
                v = sampleBilinear(vData, image.srcUvWidth, image.uvx, image.uvy, x / 2, uvY);
            }
            const int yValue = sampleBilinear(yData, image.srcWidth, image.yx, image.yy, x, y);
            const uint32_t rgb = YuvUtils::yuvToRgbFixed(yValue, u, v);
            const uint32_t r = rgb >> 16, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            hashRow[x] = tables.hashR[r] | tables.hashG[g] | tables.hashB[b];
            brightRow[x] = tables.brightR[r] + tables.brightG[g] + tables.brightB[b];
        }
    }
}

// Brush radius per pixel from the brightness variance in the (2 * kContrastRadius + 1)² window
// around it, clipped to the image: the mean of (b - center)² over the window's pixels, which is
// sum(b²) - 2 * center * sum(b) + n * center², with the sums kept as sliding windows.
class BrushSizer {
public:
    static constexpr int kRingRows = 2 * kContrastRadius + 2;

    // Working memory, reused across row chunks on the same thread.
    struct Scratch {
        std::vector<int64_t> columnSum, columnSumSq, ringSum, ringSumSq;
        void resize(int width) {
            columnSum.resize(width);
            columnSumSq.resize(width);
            ringSum.resize(static_cast<size_t>(kRingRows) * width);
            ringSumSq.resize(static_cast<size_t>(kRingRows) * width);
        }
    };

    BrushSizer(const uint32_t* brightness, int width, int height, int startRow, int maxBrushSize,
               float contrastSensitivity, Scratch& scratch)
            : brightness_(brightness), width_(width), height_(height), nextRow_(startRow),
              maxBrushSize_(maxBrushSize), contrastSensitivity_(contrastSensitivity),
              columnSum_(scratch.columnSum), columnSumSq_(scratch.columnSumSq),
              ringSum_(scratch.ringSum), ringSumSq_(scratch.ringSumSq),
              firstRowInWindow_(std::max(0, startRow - kContrastRadius)) {
        scratch.resize(width);
        std::fill(columnSum_.begin(), columnSum_.end(), 0);
        std::fill(columnSumSq_.begin(), columnSumSq_.end(), 0);
        for (int row = firstRowInWindow_; row < std::min(height, startRow + kContrastRadius); row++) {
            addRow(row);
        }
    }

    // Writes the brush radius of each pixel of the next row to `radii`.
    void nextRow(int* radii) {
        const int y = nextRow_++;
        if (y + kContrastRadius < height_) addRow(y + kContrastRadius);
        if (y - kContrastRadius - 1 >= firstRowInWindow_) removeRow(y - kContrastRadius - 1);
        const int rows = std::min(height_ - 1, y + kContrastRadius) - std::max(0, y - kContrastRadius) + 1;
        const uint32_t* center = brightness_ + static_cast<size_t>(y) * width_;
        for (int x = 0; x < width_; x++) {
            const int cols = std::min(width_ - 1, x + kContrastRadius) - std::max(0, x - kContrastRadius) + 1;
            const int64_t n = static_cast<int64_t>(rows) * cols;
            const int64_t c = center[x];
            const int64_t total = columnSumSq_[x] - 2 * c * columnSum_[x] + n * c * c;
            int radius = maxBrushSize_;
            if (total > 0) {
                // Brightness is in thousandths, so the variance is a million times too big.
                const double avgVariance = static_cast<double>(total) / (static_cast<double>(n) * 1e6);
                const double normalizedVariance = std::min(1.0, avgVariance / (255.0 * 255.0));
                const double adaptiveFactor = 1.0 - std::min(0.8, normalizedVariance * contrastSensitivity_);
                radius = std::min(maxBrushSize_, static_cast<int>(maxBrushSize_ * adaptiveFactor));
            }
            radii[x] = std::min(kMaxBrushRadius, std::max(kMinBrushRadius, radius));
        }
    }

private:
    // Horizontal window sums of row `row`, stored in its ring slot and added to the column sums.
    void addRow(int row) {
        const uint32_t* src = brightness_ + static_cast<size_t>(row) * width_;
        int64_t* sums = ringSum_.data() + static_cast<size_t>(row % kRingRows) * width_;
        int64_t* sumsSq = ringSumSq_.data() + static_cast<size_t>(row % kRingRows) * width_;
        int64_t sum = 0, sumSq = 0;
        for (int x = 0; x < std::min(width_, kContrastRadius); x++) {
            sum += src[x];
            sumSq += static_cast<int64_t>(src[x]) * src[x];
        }
        for (int x = 0; x < width_; x++) {
            const int in = x + kContrastRadius;
            if (in < width_) {
                sum += src[in];
                sumSq += static_cast<int64_t>(src[in]) * src[in];
            }
            const int out = x - kContrastRadius - 1;
            if (out >= 0) {
                sum -= src[out];
                sumSq -= static_cast<int64_t>(src[out]) * src[out];
            }
            sums[x] = sum;
            sumsSq[x] = sumSq;
            columnSum_[x] += sum;
            columnSumSq_[x] += sumSq;
        }
    }

    void removeRow(int row) {
        const int64_t* sums = ringSum_.data() + static_cast<size_t>(row % kRingRows) * width_;
        const int64_t* sumsSq = ringSumSq_.data() + static_cast<size_t>(row % kRingRows) * width_;
        for (int x = 0; x < width_; x++) {
            columnSum_[x] -= sums[x];
            columnSumSq_[x] -= sumsSq[x];
        }
    }

    const uint32_t* brightness_;
    const int width_, height_;
    int nextRow_;
    const int maxBrushSize_;
    const float contrastSensitivity_;
    std::vector<int64_t>& columnSum_;
    std::vector<int64_t>& columnSumSq_;
    std::vector<int64_t>& ringSum_;
    std::vector<int64_t>& ringSumSq_;
    const int firstRowInWindow_;
};

// Histogram of the color hashes inside the brush. The set of hashes present is kept compact, so
// finding the most common one only looks at colors actually in the brush. (Tracking the most
// common hash incrementally instead measured slower on a Pixel 8a: the extra bookkeeping on
// every pixel entering or leaving the brush cost more than the scans it saved.)
class BrushHistogram {
public:
    BrushHistogram() : counts_(kNumHashes, 0), position_(kNumHashes, 0) {
        used_.reserve(1024);
    }

    void clear() {
        for (uint16_t h : used_) counts_[h] = 0;
        used_.clear();
        changed_ = true;
    }

    void addRange(const uint16_t* row, int from, int to) {
        if (from <= to) changed_ = true;
        for (int x = from; x <= to; x++) {
            const uint16_t h = row[x];
            if (counts_[h]++ == 0) {
                position_[h] = static_cast<uint16_t>(used_.size());
                used_.push_back(h);
            }
        }
    }

    void removeRange(const uint16_t* row, int from, int to) {
        if (from <= to) changed_ = true;
        for (int x = from; x <= to; x++) {
            const uint16_t h = row[x];
            if (--counts_[h] == 0) {
                const uint16_t last = used_.back();
                const uint16_t p = position_[h];
                used_[p] = last;
                position_[last] = p;
                used_.pop_back();
            }
        }
    }

    // Replaces one pixel in the brush with another, which is a no-op if they have the same hash,
    // as neighboring pixels in smooth regions do.
    void replace(uint16_t outHash, uint16_t inHash) {
        if (outHash != inHash) {
            removeRange(&outHash, 0, 0);
            addRange(&inHash, 0, 0);
        }
    }

    // The most common hash; ties go to the smallest hash so the choice doesn't depend on the
    // order colors entered the brush. `fallback` is returned if the brush is empty.
    uint16_t dominant(uint16_t fallback) {
        if (changed_) {
            uint16_t best = 0;
            int bestCount = 0;
            for (uint16_t h : used_) {
                const int c = counts_[h];
                if (c > bestCount || (c == bestCount && h < best)) {
                    bestCount = c;
                    best = h;
                }
            }
            dominant_ = best;
            changed_ = false;
        }
        return used_.empty() ? fallback : dominant_;
    }

private:
    std::vector<uint16_t> counts_;
    std::vector<uint16_t> position_;  // Index of each present hash in used_.
    std::vector<uint16_t> used_;
    bool changed_ = true;
    uint16_t dominant_ = 0;
};

// Moves the brush from a disc of radius `oldRadius` centered at (x - 1, y) to one of radius
// `newRadius` centered at (x, y), updating the histogram with the pixels that leave and enter.
// `oldRadius` of -1 means the brush was empty.
inline void moveBrush(BrushHistogram& histogram, const uint16_t* hashPlane, int width, int height,
                      int x, int y, int oldRadius, int newRadius) {
    const auto& shapes = discShapes();
    const int maxRadius = std::max(oldRadius, newRadius);
    const int dyLo = std::max(-maxRadius, -y);
    const int dyHi = std::min(maxRadius, height - 1 - y);
    if (oldRadius == newRadius) {
        // Sliding one pixel right: on each disc row one pixel leaves on the left and one enters
        // on the right.
        for (int dy = dyLo; dy <= dyHi; dy++) {
            const uint16_t* row = hashPlane + static_cast<size_t>(y + dy) * width;
            const int half = shapes[newRadius][dy < 0 ? -dy : dy];
            const int out = x - 1 - half, in = x + half;
            if (in < width && out >= 0) {
                histogram.replace(row[out], row[in]);
            } else if (in < width) {
                histogram.addRange(row, in, in);
            } else if (out >= 0) {
                histogram.removeRange(row, out, out);
            }
        }
        return;
    }
    for (int dy = dyLo; dy <= dyHi; dy++) {
        const uint16_t* row = hashPlane + static_cast<size_t>(y + dy) * width;
        const int ady = dy < 0 ? -dy : dy;
        const int oldHalf = (oldRadius >= 0 && ady <= oldRadius) ? shapes[oldRadius][ady] : -1;
        const int newHalf = ady <= newRadius ? shapes[newRadius][ady] : -1;
        if (oldHalf < 0) {
            if (newHalf >= 0) {
                histogram.addRange(row, std::max(0, x - newHalf), std::min(width - 1, x + newHalf));
            }
            continue;
        }
        const int oldFrom = x - 1 - oldHalf, oldTo = x - 1 + oldHalf;
        if (newHalf < 0) {
            histogram.removeRange(row, std::max(0, oldFrom), std::min(width - 1, oldTo));
            continue;
        }
        const int newFrom = x - newHalf, newTo = x + newHalf;
        // Old span minus new span, then new span minus old span; each is up to two intervals.
        histogram.removeRange(row, std::max(0, oldFrom), std::min(width - 1, std::min(oldTo, newFrom - 1)));
        histogram.removeRange(row, std::max(0, std::max(oldFrom, newTo + 1)), std::min(width - 1, oldTo));
        histogram.addRange(row, std::max(0, newFrom), std::min(width - 1, std::min(newTo, oldFrom - 1)));
        histogram.addRange(row, std::max(0, std::max(newFrom, oldTo + 1)), std::min(width - 1, newTo));
    }
}

// Applies the brush to rows [startRow, endRow), writing bitmap pixels to `outputPixels` (rows
// `pixelStride` uint32s apart).
void paintRows(const ImagePlanes& planes, int width, int height, int brushSize,
               float contrastSensitivity, const ColorTables& tables, uint32_t* outputPixels,
               int pixelStride, int startRow, int endRow) {
    static thread_local BrushHistogram histogram;
    static thread_local BrushSizer::Scratch sizerScratch;
    static thread_local std::vector<int> radii;
    if (radii.size() < static_cast<size_t>(width)) radii.resize(width);
    BrushSizer sizer(planes.brightness.data(), width, height, startRow, brushSize, contrastSensitivity,
                     sizerScratch);
    const uint16_t* hashPlane = planes.hash.data();
    for (int y = startRow; y < endRow; y++) {
        sizer.nextRow(radii.data());
        histogram.clear();
        const uint16_t* hashRow = hashPlane + static_cast<size_t>(y) * width;
        uint32_t* out = outputPixels + static_cast<size_t>(y) * pixelStride;
        int previousRadius = -1;
        for (int x = 0; x < width; x++) {
            const int radius = radii[x];
            moveBrush(histogram, hashPlane, width, height, x, y, previousRadius, radius);
            out[x] = tables.bitmapColor(histogram.dominant(hashRow[x]));
            previousRadius = radius;
        }
    }
}

// Full effect: the srcWidth x srcHeight YUV planes are scaled to width x height and painted into
// `outputPixels` (rows `pixelStride` uint32s apart) as bitmap pixels.
void processImage(const uint8_t* yData, const uint8_t* uData, const uint8_t* vData, int srcWidth,
                  int srcHeight, int width, int height, int brushSize, int levels,
                  float contrastSensitivity, uint32_t* outputPixels, int pixelStride,
                  int numThreads) {
    const ColorTables tables(levels);
    const ScaledImage image(srcWidth, srcHeight, width, height);

    // Kept between frames to avoid re-faulting a fresh allocation each time.
    static thread_local ImagePlanes planes;
    const size_t numPixels = static_cast<size_t>(width) * height;
    if (planes.hash.size() < numPixels) {
        planes.hash.resize(numPixels);
        planes.brightness.resize(numPixels);
    }
    ImagePlanes& planesRef = planes;

    discShapes();  // Build once before the parallel region.
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        convertRows(yData, uData, vData, image, tables, planesRef, start, end);
    });
    vc::parallelRows(numThreads, height, [&](int start, int end) {
        paintRows(planesRef, width, height, brushSize, contrastSensitivity, tables, outputPixels,
                  pixelStride, start, end);
    });
}

}  // namespace oil

#ifndef VC_NO_JNI
// Scales the srcWidth x srcHeight planes to the size of `bitmap`, which must be RGBA_8888
// (Bitmap.Config.ARGB_8888) and width x height, and paints into it. Returns false, without
// touching the bitmap, if it can't be used; the caller then falls back.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dozingcatsoftware_vectorcamera_effect_OilPaintingEffect_00024Companion_processImageNativeToBitmap(
    JNIEnv* env,
    jobject thiz,
    jbyteArray yData,
    jbyteArray uData,
    jbyteArray vData,
    jint srcWidth,
    jint srcHeight,
    jint width,
    jint height,
    jint brushSize,
    jint levels,
    jfloat contrastSensitivity,
    jobject bitmap,
    jint numThreads
) {
    vc::LockedBitmap locked(env, bitmap, width, height);
    if (!locked.ok()) {
        return JNI_FALSE;
    }
    jbyte* yPtr = env->GetByteArrayElements(yData, nullptr);
    jbyte* uPtr = env->GetByteArrayElements(uData, nullptr);
    jbyte* vPtr = env->GetByteArrayElements(vData, nullptr);
    if (!yPtr || !uPtr || !vPtr) {
        LOGE("Failed to get native arrays");
        if (yPtr) env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
        if (uPtr) env->ReleaseByteArrayElements(uData, uPtr, JNI_ABORT);
        if (vPtr) env->ReleaseByteArrayElements(vData, vPtr, JNI_ABORT);
        return JNI_FALSE;
    }
    oil::processImage(
            reinterpret_cast<const uint8_t*>(yPtr), reinterpret_cast<const uint8_t*>(uPtr),
            reinterpret_cast<const uint8_t*>(vPtr), srcWidth, srcHeight, width, height, brushSize,
            levels, contrastSensitivity, locked.pixels(), locked.stride(),
            std::max(1, static_cast<int>(numThreads)));
    env->ReleaseByteArrayElements(yData, yPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(uData, uPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(vData, vPtr, JNI_ABORT);
    return JNI_TRUE;
}
#endif
