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

// Rounded a * b / 255, as Skia premultiplies.
inline uint32_t mulDiv255Round(uint32_t a, uint32_t b) {
    const uint32_t product = a * b + 128;
    return (product + (product >> 8)) >> 8;
}

// Converts an ARGB color int to a bitmap pixel. Effects render into bitmaps that are
// premultiplied (the default for Bitmap.createBitmap), so a translucent color's components are
// scaled by its alpha, as Bitmap.setPixels does. Used for palettes and lookup tables, not per
// pixel; kernels that compute opaque colors use packRgba.
inline uint32_t argbToRgba(uint32_t argb) {
    const uint32_t a = argb >> 24;
    uint32_t r = (argb >> 16) & 0xFFu, g = (argb >> 8) & 0xFFu, b = argb & 0xFFu;
    if (a != 0xFFu) {
        r = mulDiv255Round(a, r);
        g = mulDiv255Round(a, g);
        b = mulDiv255Round(a, b);
    }
    return (a << 24) | (b << 16) | (g << 8) | r;
}

}  // namespace vc

#endif  // VECTORCAMERA_PIXEL_FORMAT_H
