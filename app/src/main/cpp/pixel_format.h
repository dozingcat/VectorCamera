// Pixel packing for the two layouts the app uses: android.graphics.Color ARGB ints, and the
// in-memory layout of an RGBA_8888 bitmap (bytes R, G, B, A, i.e. 0xAABBGGRR as a little-endian
// int). Native effects render straight into bitmaps, so they write the bitmap layout.
#ifndef VECTORCAMERA_PIXEL_FORMAT_H
#define VECTORCAMERA_PIXEL_FORMAT_H

#include <cstdint>

namespace vc {

// Packs opaque RGB components into an ARGB color int.
inline uint32_t packArgb(uint32_t r, uint32_t g, uint32_t b) {
    return 0xFF000000u | (r << 16) | (g << 8) | b;
}

// Packs opaque RGB components into a bitmap pixel.
inline uint32_t packRgba(uint32_t r, uint32_t g, uint32_t b) {
    return 0xFF000000u | (b << 16) | (g << 8) | r;
}

// Converts an ARGB color int to an opaque bitmap pixel. Alpha is forced to 255, which makes
// premultiplication the identity.
inline uint32_t argbToRgba(uint32_t argb) {
    return 0xFF000000u | ((argb & 0xFFu) << 16) | (argb & 0xFF00u) | ((argb >> 16) & 0xFFu);
}

// Converts `count` ARGB pixels in place to opaque bitmap pixels. Vectorizes well, so converting
// a chunk of rows that is still in cache costs little.
inline void argbToRgbaInPlace(uint32_t* pixels, int count) {
    for (int i = 0; i < count; i++) {
        pixels[i] = argbToRgba(pixels[i]);
    }
}

}  // namespace vc

#endif  // VECTORCAMERA_PIXEL_FORMAT_H
