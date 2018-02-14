#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam.effect)
#pragma rs_fp_relaxed

int32_t gMultiplier = 2;
int32_t gWidth;
int32_t gHeight;
// YUV allocation from camera input. Either this or individual planes should be set, not both.
rs_allocation gYuvInput;
// Individual Y/U/V planes.
rs_allocation gYInput;
rs_allocation gUInput;
rs_allocation gVInput;

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

uchar4 RS_KERNEL setBrightnessToEdgeStrength_planar(uint32_t x, uint32_t y) {
    int edge = 0;
    if (x > 0 && x < gWidth - 1 && y > 0 && y < gHeight - 1) {
        edge = 8 * rsGetElementAt_uchar(gYInput, x, y) -
                   rsGetElementAt_uchar(gYInput, x - 1, y - 1) -
                   rsGetElementAt_uchar(gYInput, x - 1, y) -
                   rsGetElementAt_uchar(gYInput, x - 1, y + 1) -
                   rsGetElementAt_uchar(gYInput, x, y - 1) -
                   rsGetElementAt_uchar(gYInput, x, y + 1) -
                   rsGetElementAt_uchar(gYInput, x + 1, y - 1) -
                   rsGetElementAt_uchar(gYInput, x + 1, y) -
                   rsGetElementAt_uchar(gYInput, x + 1, y + 1);
    }

    uchar yy = clamp(gMultiplier * edge, 0, 255);
    uchar uu = rsGetElementAt_uchar(gUInput, x / 2, y / 2);
    uchar vv = rsGetElementAt_uchar(gVInput, x / 2, y / 2);
    return rsYuvToRGBA_uchar4(yy, uu, vv);
}
