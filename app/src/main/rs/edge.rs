#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam.effect)
#pragma rs_fp_relaxed

int32_t gMultiplier = 2;
int32_t gWidth;
int32_t gHeight;
// May be YUV data (the U and V planes will be ignored), or just the Y plane.
rs_allocation gYuvInput;
// Element.RGBA_8888, created from packed RGBA byte array.
rs_allocation gColorMap;

uchar4 RS_KERNEL computeEdgeWithColorMap(uint32_t x, uint32_t y) {
    int edge = 0;
    if (x > 0 && x < gWidth - 1 && y > 0 && y < gHeight - 1) {
        edge = 8 * rsGetElementAt_uchar(gYuvInput, x, y) -
                   rsGetElementAt_uchar(gYuvInput, x - 1, y - 1) -
                   rsGetElementAt_uchar(gYuvInput, x - 1, y) -
                   rsGetElementAt_uchar(gYuvInput, x - 1, y + 1) -
                   rsGetElementAt_uchar(gYuvInput, x, y - 1) -
                   rsGetElementAt_uchar(gYuvInput, x, y + 1) -
                   rsGetElementAt_uchar(gYuvInput, x + 1, y - 1) -
                   rsGetElementAt_uchar(gYuvInput, x + 1, y) -
                   rsGetElementAt_uchar(gYuvInput, x + 1, y + 1);
    }
    int index = clamp(gMultiplier * edge, 0, 255);
    return rsGetElementAt_uchar4(gColorMap, index);
}
