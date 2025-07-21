package com.dozingcatsoftware.util

import kotlin.math.roundToInt

/**
 * Shared YUV to RGB conversion utilities using ITU-R BT.601 conversion equations.
 */
object YuvUtils {
    
    /**
     * Convert YUV values to RGB using standard ITU-R BT.601 conversion.
     * @param y Y (luminance) component (0-255)
     * @param u U (chrominance) component (0-255)
     * @param v V (chrominance) component (0-255)
     * @param includeAlpha Whether to include alpha channel (0xFF) in the result
     * @return RGB(A) value as packed integer
     */
    fun yuvToRgb(y: Int, u: Int, v: Int, includeAlpha: Boolean = false): Int {
        val yValue = y.toFloat()
        val uValue = (u - 128).toFloat()
        val vValue = (v - 128).toFloat()

        val red = (yValue + 1.370705f * vValue).roundToInt().coerceIn(0, 255)
        val green = (yValue - 0.698001f * vValue - 0.337633f * uValue).roundToInt().coerceIn(0, 255)
        val blue = (yValue + 1.732446f * uValue).roundToInt().coerceIn(0, 255)

        return if (includeAlpha) {
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        } else {
            (red shl 16) or (green shl 8) or blue
        }
    }
} 