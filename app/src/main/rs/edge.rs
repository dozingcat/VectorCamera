#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam)
#pragma rs_fp_relaxed

int gMultiplier = 2;
int gWidth;
int gHeight;
// Created from camera input.
rs_allocation gYuvInput;
// Element.RGBA_8888, created from packed RGBA byte array.
rs_allocation gColorMap;

uchar4 RS_KERNEL computeEdgeWithColorMap(uint32_t x, uint32_t y) {
    int edge = 0;
    if (x > 0 && x < gWidth - 1 && y > 0 && y < gHeight - 1) {
        edge = 8 * rsGetElementAtYuv_uchar_Y(gYuvInput, x, y) -
                   rsGetElementAtYuv_uchar_Y(gYuvInput, x - 1, y - 1) -
                   rsGetElementAtYuv_uchar_Y(gYuvInput, x - 1, y) -
                   rsGetElementAtYuv_uchar_Y(gYuvInput, x - 1, y + 1) -
                   rsGetElementAtYuv_uchar_Y(gYuvInput, x, y - 1) -
                   rsGetElementAtYuv_uchar_Y(gYuvInput, x, y + 1) -
                   rsGetElementAtYuv_uchar_Y(gYuvInput, x + 1, y - 1) -
                   rsGetElementAtYuv_uchar_Y(gYuvInput, x + 1, y) -
                   rsGetElementAtYuv_uchar_Y(gYuvInput, x + 1, y + 1);
    }
    int index = clamp(gMultiplier * edge, 0, 255);
    return rsGetElementAt_uchar4(gColorMap, index);
}