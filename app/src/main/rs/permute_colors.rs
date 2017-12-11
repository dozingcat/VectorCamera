#pragma version(1)
#pragma rs java_package_name(com.dozingcatsoftware.boojiecam)
#pragma rs_fp_relaxed

// 1=red, 2=green, 3=blue
int32_t gRedSource;
int32_t gGreenSource;
int32_t gBlueSource;

// YUV allocation from camera input. Either this or individual planes should be set, not both.
rs_allocation gYuvInput;
// Individual Y/U/V planes.
rs_allocation gYInput;
rs_allocation gUInput;
rs_allocation gVInput;

inline static uchar extractComponent(uchar4 color, int source) {
    switch (source) {
        case 1:
            return color.r;
        case 2:
            return color.g;
        case 3:
            return color.b;
        default:
            return 0;
    }
}

uchar4 RS_KERNEL permuteColors(uint32_t x, uint32_t y) {
    uchar yy = rsGetElementAtYuv_uchar_Y(gYuvInput, x, y);
    uchar uu = rsGetElementAtYuv_uchar_U(gYuvInput, x, y);
    uchar vv = rsGetElementAtYuv_uchar_V(gYuvInput, x, y);
    uchar4 rgb = rsYuvToRGBA_uchar4(yy, uu, vv);
    uchar4 output = rgb;
    output.r = extractComponent(rgb, gRedSource);
    output.g = extractComponent(rgb, gGreenSource);
    output.b = extractComponent(rgb, gBlueSource);
    return output;
}

uchar4 RS_KERNEL permuteColors_planar(uint32_t x, uint32_t y) {
    uchar yy = rsGetElementAt_uchar(gYInput, x, y);
    uchar uu = rsGetElementAt_uchar(gUInput, x / 2, y / 2);
    uchar vv = rsGetElementAt_uchar(gVInput, x / 2, y / 2);
    uchar4 rgb = rsYuvToRGBA_uchar4(yy, uu, vv);
    uchar4 output = rgb;
    output.r = extractComponent(rgb, gRedSource);
    output.g = extractComponent(rgb, gGreenSource);
    output.b = extractComponent(rgb, gBlueSource);
    return output;
}
