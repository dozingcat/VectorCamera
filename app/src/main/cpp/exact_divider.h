// Exact integer division by a runtime constant without a divide instruction.
#ifndef VECTORCAMERA_EXACT_DIVIDER_H
#define VECTORCAMERA_EXACT_DIVIDER_H

#include <cstdint>

namespace vc {

// Unsigned division by a runtime constant using a multiply and shift. Integer division is slow
// on mobile cores (around 12 cycles, not pipelined on little cores) and blocks vectorization,
// which matters when it runs several times per pixel. With a 24-bit reciprocal the result is
// exact for all values below 2^24 / divisor, which covers sums of up to 255 * divisor (box blur
// averages) as long as divisor <= 256, and the product stays within 32 bits so the compiler can
// vectorize it.
struct ExactDivider {
    explicit ExactDivider(uint32_t divisor)
        : reciprocal(((1u << 24) + divisor - 1) / divisor) {}

    uint32_t operator()(uint32_t value) const {
        return (value * reciprocal) >> 24;
    }

    const uint32_t reciprocal;
};

}  // namespace vc

#endif  // VECTORCAMERA_EXACT_DIVIDER_H
