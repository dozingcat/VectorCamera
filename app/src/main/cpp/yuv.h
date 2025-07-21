#ifndef YUV_H
#define YUV_H

#include <algorithm>
#include <cstdint>

/**
 * Shared YUV to RGB conversion utilities using ITU-R BT.601 conversion equations.
 */
namespace YuvUtils {
    
    /**
     * Convert YUV values to RGB using floating-point arithmetic.
     * @param y Y (luminance) component (0-255)
     * @param u U (chrominance) component (0-255)
     * @param v V (chrominance) component (0-255)
     * @param includeAlpha Whether to include alpha channel (0xFF) in the result
     * @return RGB(A) value as packed integer
     */
    inline uint32_t yuvToRgbFloat(int y, int u, int v, bool includeAlpha = false) {
        float yValue = static_cast<float>(y);
        float uValue = static_cast<float>(u - 128);
        float vValue = static_cast<float>(v - 128);

        int red = static_cast<int>(yValue + 1.370705f * vValue);
        int green = static_cast<int>(yValue - 0.698001f * vValue - 0.337633f * uValue);
        int blue = static_cast<int>(yValue + 1.732446f * uValue);

        red = std::max(0, std::min(255, red));
        green = std::max(0, std::min(255, green));
        blue = std::max(0, std::min(255, blue));

        return includeAlpha ? 
            (0xFF000000 | (red << 16) | (green << 8) | blue) :
            ((red << 16) | (green << 8) | blue);
    }
    
    /**
     * Convert YUV values to RGB using fixed-point arithmetic for better performance.
     * @param y Y (luminance) component (0-255)
     * @param u U (chrominance) component (0-255)
     * @param v V (chrominance) component (0-255)
     * @param includeAlpha Whether to include alpha channel (0xFF) in the result
     * @return RGB(A) value as packed integer
     */
    inline uint32_t yuvToRgbFixed(int y, int u, int v, bool includeAlpha = false) {
        const int yValue = y;
        const int uValue = u - 128;
        const int vValue = v - 128;

        // Fixed-point coefficients (multiplied by 256)
        int red = yValue + ((351 * vValue) >> 8);           // 1.370705 * 256 ≈ 351
        int green = yValue - ((179 * vValue + 86 * uValue) >> 8); // -0.698001 * 256 ≈ -179, -0.337633 * 256 ≈ -86
        int blue = yValue + ((443 * uValue) >> 8);          // 1.732446 * 256 ≈ 443

        // Fast clamp using bitwise operations
        red = (red & ~255) ? (red < 0 ? 0 : 255) : red;
        green = (green & ~255) ? (green < 0 ? 0 : 255) : green;
        blue = (blue & ~255) ? (blue < 0 ? 0 : 255) : blue;

        return includeAlpha ? 
            (0xFF000000 | (red << 16) | (green << 8) | blue) :
            ((red << 16) | (green << 8) | blue);
    }
    
    /**
     * Alias for the default YUV to RGB conversion (using floating-point).
     */
    inline uint32_t yuvToRgb(int y, int u, int v, bool includeAlpha = false) {
        return yuvToRgbFloat(y, u, v, includeAlpha);
    }
}

#endif // YUV_H 