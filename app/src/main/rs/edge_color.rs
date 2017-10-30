#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam)
#pragma rs_fp_relaxed

int gMultiplier = 2;
int gWidth;
int gHeight;
rs_allocation gYuvInput;

uchar4 RS_KERNEL setBrightnessToEdgeStrength(uint32_t x, uint32_t y) {
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

    uchar yy = clamp(gMultiplier * edge, 0, 255);
    uchar uu = rsGetElementAtYuv_uchar_U(gYuvInput, x, y);
    uchar vv = rsGetElementAtYuv_uchar_V(gYuvInput, x, y);
    return rsYuvToRGBA_uchar4(yy, uu, vv);
}
