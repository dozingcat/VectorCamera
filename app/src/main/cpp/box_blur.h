// Separable box blur building blocks with clamped edges and exact integer averages.
#ifndef VECTORCAMERA_BOX_BLUR_H
#define VECTORCAMERA_BOX_BLUR_H

#include <algorithm>
#include <cstdint>
#include <vector>

#include "exact_divider.h"

namespace vc {

// Largest supported radius; ExactDivider is exact for kernel sizes up to 256.
constexpr int kMaxBoxBlurRadius = 127;

// Horizontal box blur of `numRows` rows of `width` bytes starting at `src`, with clamped edges.
// Rows are independent, so callers pass pointers to whichever rows they need. `srcStride` and
// `dstStride` are in bytes; a stride larger than the width lets planar data be interleaved.
inline void boxBlurRows(const uint8_t* src, int srcStride, uint8_t* dst, int dstStride,
                        int width, int radius, int numRows) {
    const ExactDivider divide(2 * radius + 1);
    for (int y = 0; y < numRows; y++) {
        const uint8_t* row = src + static_cast<size_t>(y) * srcStride;
        uint8_t* out = dst + static_cast<size_t>(y) * dstStride;
        // Running sum; the subtraction never takes it below zero so unsigned wraparound is safe.
        uint32_t total = 0;
        for (int k = -radius; k <= radius; k++) {
            total += row[std::max(0, std::min(k, width - 1))];
        }
        for (int x = 0; x < width; x++) {
            out[x] = static_cast<uint8_t>(divide(total));
            total += row[std::min(x + radius + 1, width - 1)] - row[std::max(x - radius, 0)];
        }
    }
}

inline void boxBlurRows(const uint8_t* src, uint8_t* dst, int width, int radius, int numRows) {
    boxBlurRows(src, width, dst, width, width, radius, numRows);
}

// Vertical box blur over a buffer of `height` rows, producing rows one at a time so the caller
// can post-process each (quantize, pack channels, ...). Row indices outside [0, height) are
// clamped, matching a full-image blur, so a caller holding only a band of rows plus `radius`
// halo rows above and below gets exactly the full-image result for the band.
class ColumnBoxBlur {
public:
    // `src` holds `height` rows of `width` bytes, `stride` bytes apart. Seeds the running column
    // sums for output row `startRow`.
    ColumnBoxBlur(const uint8_t* src, int stride, int width, int height, int radius, int startRow)
        : src_(src), stride_(stride), width_(width), height_(height), radius_(radius),
          divide_(2 * radius + 1), row_(startRow), totals_(width, 0) {
        for (int k = -radius; k <= radius; k++) {
            const uint8_t* row = rowPtr(startRow + k);
            for (int x = 0; x < width; x++) {
                totals_[x] += row[x];
            }
        }
    }

    // Writes the blurred values of the current row to `out` and advances to the next row.
    void nextRow(uint8_t* out) {
        const uint8_t* addRow = rowPtr(row_ + radius_ + 1);
        const uint8_t* subRow = rowPtr(row_ - radius_);
        for (int x = 0; x < width_; x++) {
            out[x] = static_cast<uint8_t>(divide_(totals_[x]));
            totals_[x] += addRow[x] - subRow[x];
        }
        row_++;
    }

private:
    const uint8_t* rowPtr(int y) const {
        return src_ + static_cast<size_t>(std::max(0, std::min(y, height_ - 1))) * stride_;
    }

    const uint8_t* src_;
    const int stride_, width_, height_, radius_;
    const ExactDivider divide_;
    int row_;
    std::vector<uint32_t> totals_;
};

}  // namespace vc

#endif  // VECTORCAMERA_BOX_BLUR_H
